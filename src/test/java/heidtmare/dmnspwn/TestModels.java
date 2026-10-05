package heidtmare.dmnspwn;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.util.unit.DataSize;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.xml.DmnDocument;

public final class TestModels {

    private TestModels() {
    }

    public static String xml(String sample) {
        try (InputStream in = TestModels.class.getResourceAsStream("/samples/" + sample + ".dmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** File storage in {@code dir}, S3 disabled. */
    public static DmnProperties properties(Path dir, int historySize) {
        return new DmnProperties(DmnProperties.Storage.FILE, dir, false, historySize, new DmnProperties.S3(false,
                null, "", "dmnspwn/", null, null, false, DataSize.ofKilobytes(64), Duration.ofSeconds(5)));
    }

    /** Bucket {@code bucket}: models are loaded from / published to {@code dmn/}, S3 storage uses {@code store/}. */
    public static DmnProperties s3Properties(DmnProperties.Storage storage, Path dir, int historySize) {
        return new DmnProperties(storage, dir, false, historySize, new DmnProperties.S3(true, "bucket", "/dmn",
                "store/", "us-east-1", (URI) null, false, DataSize.ofKilobytes(64), Duration.ofSeconds(5)));
    }

    public static DmnDocument loan() {
        return DmnDocument.parse(xml("loan-eligibility"));
    }

    public static DmnDocument dish() {
        return DmnDocument.parse(xml("dish-selection"));
    }
}
