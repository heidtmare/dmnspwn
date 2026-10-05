package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import heidtmare.dmnspwn.store.FileModelStore;
import heidtmare.dmnspwn.store.NotFoundException;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.ModelStore.Entry;

class FileModelStoreTest extends ModelStoreContract {

    @TempDir
    Path storage;

    @Override
    FileModelStore store(int historySize) {
        return new FileModelStore(TestModels.properties(storage, historySize));
    }

    private long historyFiles(String id) throws IOException {
        try (Stream<Path> files = Files.list(storage.resolve(".history").resolve(id))) {
            return files.count();
        }
    }

    @Test
    void ignoresForeignFilesInTheHistoryDirectory() throws IOException {
        ModelStore store = store(1);
        ModelService models = new ModelService(store);
        store.write("m", "v1", null, false);
        save(store, "m", "v2");
        Path dir = storage.resolve(".history").resolve("m");
        Files.writeString(dir.resolve(".DS_Store"), "");
        Files.writeString(dir.resolve("notes.dmn"), "");

        save(store, "m", "v3");

        assertThat(historyFiles("m")).isEqualTo(3);
        assertThat(models.undo("m")).isTrue();
        assertThat(content(store, "m")).isEqualTo("v2");
        assertThat(store.hasHistory("m")).isFalse();
    }

    @Test
    void renumbersSnapshotsOfEarlierVersionsInOrder() throws IOException {
        ModelStore store = store(5);
        ModelService models = new ModelService(store);
        store.write("m", "current", null, false);
        Path dir = storage.resolve(".history").resolve("m");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("900-1.dmn"), "older");
        Files.writeString(dir.resolve("1000-1.dmn"), "newer");
        Files.writeString(dir.resolve("1000-0.dmn"), "middle");

        assertThat(store.hasHistory("m")).isTrue();
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("000000000001.dmn", "000000000002.dmn", "000000000003.dmn");
        }
        assertThat(models.undo("m")).isTrue();
        assertThat(content(store, "m")).isEqualTo("newer");
        assertThat(models.undo("m")).isTrue();
        assertThat(content(store, "m")).isEqualTo("middle");
        assertThat(models.undo("m")).isTrue();
        assertThat(content(store, "m")).isEqualTo("older");
    }

    @Test
    void rejectsInvalidIdsBeforeLocking() {
        ModelService models = new ModelService(store(1));

        assertThatThrownBy(() -> models.atomically("../m", () -> null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void ignoresFilesThatAreNotModels() throws IOException {
        ModelStore store = store(1);
        store.write("a", "x", null, false);
        Files.writeString(storage.resolve("notes.txt"), "");
        Files.writeString(storage.resolve("-bad.dmn"), "");
        Files.createDirectories(storage.resolve("dir.dmn"));

        assertThat(store.list()).extracting(Entry::id).containsExactly("a");
        assertThat(store.exists("dir")).isFalse();
    }

    @Test
    void noticesFilesChangedOutsideTheApplication() throws IOException {
        ModelStore store = store(1);
        store.write("m", "v1", null, false);
        var before = store.read("m").orElseThrow();
        Files.writeString(storage.resolve("m.dmn"), "edited by hand, longer");

        assertThat(store.stamp("m")).isPresent().get().isNotEqualTo(before.stamp());
        assertThat(content(store, "m")).isEqualTo("edited by hand, longer");
    }
}
