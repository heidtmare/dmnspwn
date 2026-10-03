package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import heidtmare.dmnspwn.config.DmnProperties;
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
}
