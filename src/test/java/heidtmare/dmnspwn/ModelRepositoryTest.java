package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.store.ModelNotFoundException;
import heidtmare.dmnspwn.store.ModelRepository;

class ModelRepositoryTest {

    @TempDir
    Path storage;

    private ModelRepository repository(int historySize) {
        return new ModelRepository(new DmnProperties(storage, false, historySize,
                new DmnProperties.S3(false, null, "", null, null, false, DataSize.ofMegabytes(1), Duration.ofSeconds(1))));
    }

    private long historyFiles(String id) throws IOException {
        try (Stream<Path> files = Files.list(storage.resolve(".history").resolve(id))) {
            return files.count();
        }
    }

    @Test
    void keepsTheNewestSnapshotsAndUndoesInOrder() {
        ModelRepository repo = repository(2);
        repo.write("m", "v1", false);
        for (String v : new String[] {"v2", "v3", "v4"}) {
            repo.write("m", v, true);
        }

        assertThat(repo.undo("m")).isTrue();
        assertThat(repo.read("m")).contains("v3");
        assertThat(repo.undo("m")).isTrue();
        assertThat(repo.read("m")).contains("v2");
        assertThat(repo.undo("m")).isFalse();
    }

    @Test
    void ignoresForeignFilesInTheHistoryDirectory() throws IOException {
        ModelRepository repo = repository(1);
        repo.write("m", "v1", false);
        repo.write("m", "v2", true);
        Path dir = storage.resolve(".history").resolve("m");
        Files.writeString(dir.resolve(".DS_Store"), "");
        Files.writeString(dir.resolve("notes.dmn"), "");

        repo.write("m", "v3", true);

        assertThat(historyFiles("m")).isEqualTo(3);
        assertThat(repo.undo("m")).isTrue();
        assertThat(repo.read("m")).contains("v2");
        assertThat(repo.hasHistory("m")).isFalse();
    }

    @Test
    void ordersSnapshotsNumericallyNotByName() throws IOException {
        ModelRepository repo = repository(5);
        repo.write("m", "current", false);
        Path dir = storage.resolve(".history").resolve("m");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("900-1.dmn"), "older");
        Files.writeString(dir.resolve("1000-1.dmn"), "newer");
        Files.writeString(dir.resolve("1000-0.dmn"), "middle");

        assertThat(repo.undo("m")).isTrue();
        assertThat(repo.read("m")).contains("newer");
        assertThat(repo.undo("m")).isTrue();
        assertThat(repo.read("m")).contains("middle");
        assertThat(repo.undo("m")).isTrue();
        assertThat(repo.read("m")).contains("older");
    }

    @Test
    void keepsNoHistoryWhenTheSizeIsZero() {
        ModelRepository repo = repository(0);
        repo.write("m", "v1", false);
        repo.write("m", "v2", true);

        assertThat(repo.hasHistory("m")).isFalse();
        assertThat(repo.undo("m")).isFalse();
        assertThat(repo.read("m")).contains("v2");
    }

    @Test
    void listsOnlyValidModelFiles() throws IOException {
        ModelRepository repo = repository(1);
        repo.write("b", "x", false);
        repo.write("a", "x", false);
        Files.writeString(storage.resolve("notes.txt"), "");
        Files.writeString(storage.resolve("-bad.dmn"), "");
        Files.createDirectories(storage.resolve("dir.dmn"));

        assertThat(repo.ids()).containsExactly("a", "b");
        assertThat(repo.exists("dir")).isFalse();
        assertThat(repo.read("../a")).isEmpty();
        assertThatThrownBy(() -> repo.write("../a", "x", false)).isInstanceOf(ModelNotFoundException.class);
    }

    @Test
    void deletingAModelRemovesItsHistoryAndMetadata() {
        ModelRepository repo = repository(2);
        repo.write("m", "v1", false);
        repo.write("m", "v2", true);
        Properties props = new Properties();
        props.setProperty("key", "value");
        repo.writeMeta("m", props);

        repo.delete("m");

        assertThat(repo.exists("m")).isFalse();
        assertThat(repo.hasHistory("m")).isFalse();
        assertThat(repo.readMeta("m")).isEmpty();
        assertThat(storage.resolve(".history").resolve("m")).doesNotExist();
    }
}
