package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import heidtmare.dmnspwn.edit.DmnEditor;
import heidtmare.dmnspwn.edit.Forms.DecisionTableForm;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.Scope;
import heidtmare.dmnspwn.eval.Values;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.Views.ConnectionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Guards against the quadratic paths fixed in id generation, element views and FEEL parsing. */
class PerformanceTest {

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
}
