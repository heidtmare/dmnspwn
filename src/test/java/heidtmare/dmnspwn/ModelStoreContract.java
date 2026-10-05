package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import heidtmare.dmnspwn.store.ModelNotFoundException;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.ModelStore.Entry;
import heidtmare.dmnspwn.store.ModelStore.Stored;
import heidtmare.dmnspwn.store.StoreConflictException;

/** Behaviour every {@link ModelStore} must have; run by one subclass per implementation. */
abstract class ModelStoreContract {

    abstract ModelStore store(int historySize);

    /** Writes the next version of a model, based on the current one. */
    static void save(ModelStore store, String id, String content) {
        store.write(id, content, store.read(id).orElse(null), true);
    }

    static String content(ModelStore store, String id) {
        return store.read(id).map(Stored::content).orElse(null);
    }

    @Test
    void keepsTheNewestSnapshotsAndUndoesInOrder() {
        ModelStore store = store(2);
        ModelService models = new ModelService(store);
        store.write("m", "v1", null, false);
        for (String v : new String[] {"v2", "v3", "v4"}) {
            save(store, "m", v);
        }

        assertThat(models.undo("m")).isTrue();
        assertThat(content(store, "m")).isEqualTo("v3");
        assertThat(models.undo("m")).isTrue();
        assertThat(content(store, "m")).isEqualTo("v2");
        assertThat(models.undo("m")).isFalse();
        assertThat(store.hasHistory("m")).isFalse();
    }

    @Test
    void historyContinuesInOrderAfterAnUndo() {
        ModelStore store = store(10);
        ModelService models = new ModelService(store);
        store.write("m", "v1", null, false);
        save(store, "m", "v2");
        save(store, "m", "v3");
        models.undo("m");
        save(store, "m", "v4");

        models.undo("m");
        assertThat(content(store, "m")).isEqualTo("v2");
        models.undo("m");
        assertThat(content(store, "m")).isEqualTo("v1");
    }

    @Test
    void keepsNoHistoryWhenTheSizeIsZero() {
        ModelStore store = store(0);
        store.write("m", "v1", null, false);
        save(store, "m", "v2");

        assertThat(store.hasHistory("m")).isFalse();
        assertThat(new ModelService(store).undo("m")).isFalse();
        assertThat(content(store, "m")).isEqualTo("v2");
    }

    @Test
    void writesOnlyOnTopOfTheVersionThatWasRead() {
        ModelStore store = store(5);
        store.write("m", "v1", null, false);
        Stored v1 = store.read("m").orElseThrow();
        store.write("m", "v2", v1, true);

        assertThatThrownBy(() -> store.write("m", "lost", v1, true)).isInstanceOf(StoreConflictException.class);
        assertThatThrownBy(() -> store.write("m", "lost", null, false)).isInstanceOf(StoreConflictException.class)
                .hasMessageContaining("already exists");
        assertThat(content(store, "m")).isEqualTo("v2");
        store.delete("m");
        assertThatThrownBy(() -> store.write("m", "lost", v1, false)).isInstanceOf(StoreConflictException.class);
        assertThat(store.exists("m")).isFalse();
    }

    @Test
    void stampsChangeWhenTheContentChanges() {
        ModelStore store = store(1);
        store.write("m", "v1", null, false);
        var before = store.stamp("m").orElseThrow();
        save(store, "m", "v2");
        assertThat(store.stamp("m")).isPresent().get().isNotEqualTo(before);
        assertThat(store.list()).extracting(Entry::id).containsExactly("m");
    }

    @Test
    void testScenariosAreWrittenConditionally() {
        ModelStore store = store(1);
        store.write("m", "model", null, false);
        assertThat(store.readTests("m")).isEmpty();
        store.writeTests("m", "t1", null);
        Stored t1 = store.readTests("m").orElseThrow();
        assertThat(t1.content()).isEqualTo("t1");
        assertThatThrownBy(() -> store.writeTests("m", "lost", null)).isInstanceOf(StoreConflictException.class);

        store.writeTests("m", "t2", t1);
        assertThatThrownBy(() -> store.writeTests("m", "lost", t1)).isInstanceOf(StoreConflictException.class);
        store.writeTests("m", null, store.readTests("m").orElseThrow());
        assertThat(store.readTests("m")).isEmpty();
    }

    @Test
    void listsOnlyValidIds() {
        ModelStore store = store(1);
        store.write("b", "x", null, false);
        store.write("a", "x", null, false);
        store.writeTests("a", "t", null);

        assertThat(store.list()).extracting(Entry::id).containsExactly("a", "b");
        assertThat(store.read("../a")).isEmpty();
        assertThat(store.exists("../a")).isFalse();
        assertThatThrownBy(() -> store.write("../a", "x", null, false)).isInstanceOf(ModelNotFoundException.class);
    }

    @Test
    void deletingAModelRemovesItsHistoryTestsAndMetadata() {
        ModelStore store = store(2);
        store.write("m", "v1", null, false);
        save(store, "m", "v2");
        store.writeTests("m", "t", null);
        Properties props = new Properties();
        props.setProperty("key", "value");
        store.writeMeta("m", props);
        store.write("other", "x", null, false);
        store.writeMeta("other", props);
        assertThat(store.readAllMeta()).containsOnlyKeys("m", "other");

        store.delete("m");

        assertThat(store.exists("m")).isFalse();
        assertThat(store.hasHistory("m")).isFalse();
        assertThat(store.readTests("m")).isEmpty();
        assertThat(store.readMeta("m")).isEmpty();
        assertThat(store.readAllMeta()).containsOnlyKeys("other");
        assertThat(store.readMeta("other").getProperty("key")).isEqualTo("value");
    }
}
