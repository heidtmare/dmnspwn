package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.w3c.dom.Element;
import org.camunda.feel.syntaxtree.Val;
import org.w3c.dom.NodeList;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.edit.DmnEditor;
import heidtmare.dmnspwn.edit.Forms.DecisionTableForm;
import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.eval.Scope;
import heidtmare.dmnspwn.eval.Values;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.Views.ConnectionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.store.ModelRepository;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.store.ModelSummary;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnFormatException;
import heidtmare.dmnspwn.xml.DmnXml;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Guards against repeated work: id generation, element views, FEEL parsing, the model listing, XML factories and
 * evaluation bookkeeping.
 */
class PerformanceTest {

    @TempDir
    Path dir;

    private static final int RULES = 2_000;

    private static void tableAction(DmnEditor ed, String action) {
        DecisionTableForm f = new DecisionTableForm();
        f.setHitPolicy("UNIQUE");
        f.setAction(action);
        ed.saveDecisionTable("Risk_Category", f);
    }

    @Test
    void editsLargeDecisionTablesQuicklyWithUniqueIds() {
        DmnDocument doc = TestModels.loan();
        DmnEditor ed = new DmnEditor(doc);
        // Each new id used to scan the whole DOM, so these edits grew quadratically with the table.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < RULES; i++) {
                tableAction(ed, "addRule");
            }
            tableAction(ed, "addInput:0");
            tableAction(ed, "addOutput");
            tableAction(ed, "duplicateRule:0");
        });

        Set<String> ids = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        NodeList all = doc.dom().getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            String id = ((Element) all.item(i)).getAttribute("id");
            if (!id.isEmpty() && !ids.add(id)) {
                duplicates.add(id);
            }
        }
        assertThat(duplicates).isEmpty();
        assertThat(ids.size()).isGreaterThan(RULES * 4);
    }

    @Test
    void buildsElementViewsOnceWithIndexedConnections() {
        DmnReader reader = new DmnReader(TestModels.loan());
        assertThat(reader.elements()).isSameAs(reader.elements());

        // The index must give the same answer as filtering every connection.
        List<ConnectionView> connections = reader.connections();
        for (ElementView e : reader.elements()) {
            String id = e.id();
            assertThat(e.requires()).containsExactlyElementsOf(connections.stream()
                    .filter(c -> c.targetId().equals(id) && c.kind() != ConnectionKind.ASSOCIATION).toList());
            assertThat(e.requiredBy()).containsExactlyElementsOf(connections.stream()
                    .filter(c -> c.sourceId().equals(id) && c.sourceLocal() || c.kind() == ConnectionKind.ASSOCIATION
                            && (c.sourceId().equals(id) || c.targetId().equals(id)))
                    .toList());
        }
    }

    @Test
    void sharesParsesBetweenEqualNameSetsOnly() {
        assertThat(Feel.QuotedNames.of(List.of("Applicant Age", "x")))
                .isEqualTo(Feel.QuotedNames.of(Set.of("x", "Applicant Age")))
                .hasSameHashCodeAs(Feel.QuotedNames.of(List.of("x", "Applicant Age")))
                .isNotEqualTo(Feel.QuotedNames.of(List.of("Credit Score")));

        Feel feel = new Feel();
        Scope quoted = Scope.root(Feel.QuotedNames.of(List.of("Applicant Age"))).put("Applicant Age", Values.number(30));
        Scope plain = Scope.empty().put("Applicant Age", Values.number(30));
        assertThat(Values.format(feel.evaluate("Applicant Age + 1", quoted).value())).isEqualTo("31");
        // Same text, different names: must not reuse the quoted parse from the cache.
        assertThat(Values.format(feel.evaluate("Applicant Age + 1", plain).value())).isNotEqualTo("31");
        assertThat(Values.format(feel.evaluate("Applicant Age + 1", quoted).value())).isEqualTo("31");
    }

    @Test
    void listsModelsWithoutReparsingUnchangedOnes() throws Exception {
        DmnProperties props = new DmnProperties(dir, false, 10, new DmnProperties.S3(false, "", "", "",
                (URI) null, false, DataSize.ofKilobytes(64), Duration.ofSeconds(5)));
        ModelRepository repo = spy(new ModelRepository(props));
        ModelService models = new ModelService(repo);
        String loan = models.importXml("loan.dmn", TestModels.xml("loan-eligibility"));
        String dish = models.importXml("dish.dmn", TestModels.xml("dish-selection"));
        assertThat(models.list()).extracting(ModelSummary::id).containsExactlyInAnyOrder(loan, dish);

        clearInvocations(repo);
        models.list();
        verify(repo, never()).read(anyString());

        // An edit through the application is picked up...
        models.update(loan, ed -> {
            ed.updateDefinitions("Renamed", "urn:renamed", null);
            return null;
        });
        assertThat(summary(models, loan).name()).isEqualTo("Renamed");
        // ...and so is a change made to the file outside it.
        Files.writeString(dir.resolve(dish + ".dmn"), "not xml");
        assertThat(summary(models, dish).error()).isNotNull();

        models.delete(dish);
        assertThat(models.list()).extracting(ModelSummary::id).containsExactly(loan);
    }

    private static ModelSummary summary(ModelService models, String id) {
        return models.list().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void reusesXmlParsersSafelyAcrossThreadsAndFailures() throws Exception {
        String loan = TestModels.xml("loan-eligibility");
        String expected = DmnDocument.parse(loan).toXml();
        // A failed parse must leave the thread's parser usable and still strict.
        assertThatThrownBy(() -> DmnXml.parse("<definitions")).isInstanceOf(DmnFormatException.class);
        assertThat(DmnDocument.parse(loan).toXml()).isEqualTo(expected);
        assertThatThrownBy(() -> DmnXml.parse("<a></b>")).isInstanceOf(DmnFormatException.class);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(() -> DmnDocument.parse(loan).toXml()));
            }
            for (Future<String> f : results) {
                assertThat(f.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void listsFunctionNamesWithoutEvaluatingAnything() {
        AtomicInteger resolved = new AtomicInteger();
        Scope root = Scope.root(Feel.QuotedNames.of(List.of()), new Scope.Resolver() {
            @Override
            public Set<String> names() {
                return Set.of("decision", "bkm", "shadowed");
            }

            @Override
            public Val resolve(String name) {
                resolved.incrementAndGet();
                return Values.number(1);
            }

            @Override
            public Set<String> functionNames() {
                return Set.of("bkm", "shadowed");
            }
        });
        Scope inner = root.child().put("shadowed", Values.number(2));

        assertThat(CollectionConverters.asJava(inner.functionProvider().functionNames())).containsExactly("bkm");
        assertThat(resolved).hasValue(0);
    }

    @Test
    void evaluatorComputesFormDataOnceAndKeepsMessagePrefixes() {
        ModelEvaluator evaluator = new ModelEvaluator(new DmnReader(TestModels.loan()), new Feel());
        assertThat(evaluator.inputFields()).isSameAs(evaluator.inputFields());
        assertThat(evaluator.decisions()).isSameAs(evaluator.decisions());

        Evaluation e = evaluator.evaluate(Map.of(), List.of("Risk_Category"), null);
        assertThat(e.decisions()).filteredOn(d -> d.id().equals("Risk_Category")).singleElement()
                .satisfies(d -> assertThat(d.messages()).anyMatch(m -> m.text().startsWith("Rule ")));
    }
}
