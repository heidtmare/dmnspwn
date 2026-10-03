package heidtmare.dmnspwn;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

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

    public static DmnDocument loan() {
        return DmnDocument.parse(xml("loan-eligibility"));
    }

    public static DmnDocument dish() {
        return DmnDocument.parse(xml("dish-selection"));
    }
}
