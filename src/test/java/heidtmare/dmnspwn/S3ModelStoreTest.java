package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.s3.S3ModelStore;
import heidtmare.dmnspwn.scenario.Scenario;
import heidtmare.dmnspwn.scenario.ScenarioService;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.StoreConflictException;

class S3ModelStoreTest extends ModelStoreContract {

    @TempDir
    Path dir;

    final FakeS3Client s3 = new FakeS3Client();

    /** Each call is a separate instance (pod) sharing the one bucket. */
    @Override
    S3ModelStore store(int historySize) {
        return new S3ModelStore(s3, TestModels.s3Properties(DmnProperties.Storage.S3, dir, historySize));
    }

    @Test
    void keepsEverythingUnderTheStoragePrefix() {
        ModelStore store = store(5);
        store.write("m", "v1", null, false);
        save(store, "m", "v2");
        store.writeTests("m", "t", null);
        var props = new java.util.Properties();
        props.setProperty("k", "v");
        store.writeMeta("m", props);

        assertThat(s3.objects).containsOnlyKeys("store/m.dmn", "store/m.tests.xml",
                "store/.history/m/000000000001.dmn", "store/.meta/m.properties");
        assertThat(s3.objects.get("store/m.dmn").metadata()).containsEntry("revision", "2");
        assertThat(store.location()).isEqualTo("s3://bucket/store/");
    }

    @Test
    void revalidatesCachedContentInsteadOfDownloadingItAgain() {
        ModelStore store = store(5);
        store.write("m", "v1", null, false);
        content(store, "m");
        int full = s3.fullReads.get();

        assertThat(content(store, "m")).isEqualTo("v1");
        assertThat(s3.fullReads).hasValue(full);
        assertThat(s3.notModified).hasPositiveValue();

        // a write by another instance is seen on the next read
        save(store(5), "m", "v2");
        assertThat(content(store, "m")).isEqualTo("v2");
    }

    @Test
    void keepsTheHistoryInOrderWhenTheObjectWasReplacedOutsideTheApplication() {
        ModelStore store = store(10);
        ModelService models = new ModelService(store);
        store.write("m", "v1", null, false);
        save(store, "m", "v2");
        save(store, "m", "v3");
        s3.store("store/m.dmn", "uploaded");  // no revision metadata
        save(store, "m", "v4");

        models.undo("m");
        assertThat(content(store, "m")).isEqualTo("uploaded");
        models.undo("m");
        assertThat(content(store, "m")).isEqualTo("v2");
    }

    @Test
    void instancesSharingTheBucketSeeEachOthersChanges() {
        ModelService a = new ModelService(store(10));
        ModelService b = new ModelService(store(10));
        String id = a.importXml("loan.dmn", TestModels.xml("loan-eligibility"));

        b.edit(id, ed -> ed.addElement(ElementKind.DECISION, "Added by B", null));
        assertThat(a.reader(id).elements()).anyMatch(e -> "Added by B".equals(e.name()));
        assertThat(a.list()).singleElement().satisfies(m -> assertThat(m.id()).isEqualTo(id));

        assertThat(a.undo(id)).isTrue();
        assertThat(b.reader(id).elements()).noneMatch(e -> "Added by B".equals(e.name()));
        b.delete(id);
        assertThat(a.list()).isEmpty();
    }

    @Test
    void anEditThatRacesWithAnotherInstanceIsAppliedToTheNewVersion() {
        ModelService a = new ModelService(store(10));
        ModelService b = new ModelService(store(10));
        String id = a.importXml("loan.dmn", TestModels.xml("loan-eligibility"));
        AtomicInteger attempts = new AtomicInteger();

        a.edit(id, ed -> {
            if (attempts.incrementAndGet() == 1) {
                // B saves between A's read and A's write
                b.edit(id, other -> other.addElement(ElementKind.DECISION, "From B", null));
            }
            ed.addElement(ElementKind.DECISION, "From A", null);
        });

        assertThat(attempts).hasValue(2);
        assertThat(b.reader(id).elements()).extracting(e -> e.name()).contains("From A", "From B");
        assertThat(a.undo(id)).isTrue();
        assertThat(a.reader(id).elements()).extracting(e -> e.name()).contains("From B").doesNotContain("From A");
    }

    @Test
    void givesUpWhenTheModelKeepsChanging() {
        ModelService a = new ModelService(store(10));
        ModelService b = new ModelService(store(10));
        String id = a.importXml("loan.dmn", TestModels.xml("loan-eligibility"));
        AtomicInteger n = new AtomicInteger();

        assertThatThrownBy(() -> a.edit(id, ed -> b.edit(id, other -> other.addElement(ElementKind.DECISION,
                "B" + n.incrementAndGet(), null)))).isInstanceOf(StoreConflictException.class)
                .hasMessageContaining("try again");
        assertThat(n).hasValue(5);
    }

    @Test
    void testScenariosSavedByTwoInstancesAreBothKept() {
        Feel feel = new Feel();
        ModelStore storeA = store(10);
        ModelStore storeB = store(10);
        ModelService a = new ModelService(storeA);
        ModelService b = new ModelService(storeB);
        ScenarioService testsA = new ScenarioService(a, storeA, feel);
        ScenarioService testsB = new ScenarioService(b, storeB, feel);
        String id = a.importXml("dish.dmn", TestModels.xml("dish-selection"));

        testsA.list(id);
        testsA.save(id, new Scenario("first", Map.of(), Map.of()));
        testsB.save(id, new Scenario("second", Map.of(), Map.of()));
        assertThat(testsA.list(id)).extracting(Scenario::name).containsExactly("first", "second");
        testsA.delete(id, 0);
        assertThat(testsB.list(id)).extracting(Scenario::name).containsExactly("second");
    }
}
