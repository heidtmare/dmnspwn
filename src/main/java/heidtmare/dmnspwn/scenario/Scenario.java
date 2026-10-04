package heidtmare.dmnspwn.scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A saved test case of a model: input data values and the results expected of some decisions, all written as FEEL
 * expressions and keyed by element name (as in the DMN TCK). Inputs left out are {@code null}.
 */
public record Scenario(String name, Map<String, String> inputs, Map<String, String> expected) {

    public Scenario {
        name = name == null ? "" : name.strip();
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
    }

    public Scenario withExpected(Map<String, String> expected) {
        return new Scenario(name, inputs, expected);
    }
}
