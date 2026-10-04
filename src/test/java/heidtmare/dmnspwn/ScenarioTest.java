package heidtmare.dmnspwn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import heidtmare.dmnspwn.edit.DmnEditException;
import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.scenario.Scenario;
import heidtmare.dmnspwn.scenario.ScenarioRunner;
import heidtmare.dmnspwn.scenario.TestCases;
import heidtmare.dmnspwn.scenario.TestReport.ScenarioResult;
import heidtmare.dmnspwn.xml.DmnFormatException;

class ScenarioTest {

    private static final Feel FEEL = new Feel();

    private static ScenarioRunner dishRunner() {
        return new ScenarioRunner(new ModelEvaluator(new DmnReader(TestModels.dish()), FEEL), FEEL);
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Test
    void roundTripsValuesThroughTheTckFormat() {
        Scenario s = new Scenario("Typed values", map(
                "Number", "-12.50", "Text", "\"say \\\"hi\\\"\"", "Flag", "true", "Day", "date(\"2024-01-31\")",
                "Moment", "date and time(\"2024-01-31T10:30:00\")", "Clock", "time(\"10:30:00\")",
                "Span", "duration(\"P1DT2H\")", "Months", "duration(\"P1Y6M\")", "Nothing", "null",
                "Items", "[1, \"two\", [3]]", "Record", "{amount: 1000, \"due date\": date(\"2024-02-01\")}"),
                map("Result", "\"ok\""));

        String xml = TestCases.write("typed.dmn", List.of(s), FEEL);
        assertThat(xml).contains("<testCases xmlns=\"" + TestCases.NS + "\"")
                .contains("<modelName>typed.dmn</modelName>")
                .contains("xsi:type=\"xsd:decimal\">-12.5</value>")
                .contains("xsi:type=\"xsd:date\">2024-01-31</value>")
                .contains("xsi:nil=\"true\"")
                .contains("<component name=\"due date\">");

        Scenario read = TestCases.read(xml).getFirst();
        assertThat(read.name()).isEqualTo("Typed values");
        assertThat(read.inputs()).containsExactly(
                Map.entry("Number", "-12.5"), Map.entry("Text", "\"say \\\"hi\\\"\""), Map.entry("Flag", "true"),
                Map.entry("Day", "date(\"2024-01-31\")"), Map.entry("Moment", "date and time(\"2024-01-31T10:30:00\")"),
                Map.entry("Clock", "time(\"10:30:00\")"), Map.entry("Span", "duration(\"P1DT2H\")"),
                Map.entry("Months", "duration(\"P1Y6M\")"), Map.entry("Nothing", "null"),
                Map.entry("Items", "[1, \"two\", [3]]"),
                Map.entry("Record", "{amount: 1000, \"due date\": date(\"2024-02-01\")}"));
        assertThat(read.expected()).containsExactly(Map.entry("Result", "\"ok\""));
    }

    @Test
    void readsTestCasesWrittenByOtherTools() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testCases xmlns="http://www.omg.org/spec/DMN/20160719/testcase"
                           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <modelName>dish-selection.dmn</modelName>
                  <testCase id="001">
                    <description>Big winter party</description>
                    <inputNode name="Season"><value xsi:type="xs:string">Winter</value></inputNode>
                    <inputNode name="Guest Count"><value xsi:type="xs:double">1.2E1</value></inputNode>
                    <resultNode name="Dish" type="decision"><expected><value>Stew</value></expected></resultNode>
                  </testCase>
                  <testCase id="002" type="bkm" invocableName="something">
                    <resultNode name="x"><expected><value>1</value></expected></resultNode>
                  </testCase>
                </testCases>
                """;
        List<Scenario> scenarios = TestCases.read(xml);
        assertThat(scenarios).hasSize(1);
        Scenario s = scenarios.getFirst();
        assertThat(s.name()).isEqualTo("Big winter party");
        assertThat(s.inputs()).containsExactly(Map.entry("Season", "\"Winter\""), Map.entry("Guest Count", "12"));
        assertThat(s.expected()).containsExactly(Map.entry("Dish", "\"Stew\""));

        assertThatThrownBy(() -> TestCases.read("<definitions/>")).isInstanceOf(DmnFormatException.class);
    }

    @Test
    void refusesValuesATestFileCannotHold() {
        Scenario s = new Scenario("f", map("x", "function(a) a"), map("y", "1"));
        assertThatThrownBy(() -> TestCases.write("m.dmn", List.of(s), FEEL)).isInstanceOf(DmnEditException.class);
        Scenario broken = new Scenario("b", map("x", "1 +"), map("y", "1"));
        assertThatThrownBy(() -> TestCases.write("m.dmn", List.of(broken), FEEL))
                .isInstanceOf(DmnEditException.class).hasMessageContaining("Test 'b', x");
    }

    @Test
    void comparesExpectedResultsWithTheModel() {
        ScenarioRunner runner = dishRunner();
        Map<String, String> inputs = map("Season", "\"Fall\"", "Guest Count", "3", "Guests with children?", "true");

        ScenarioResult pass = runner.run(0, new Scenario("pass", inputs,
                map("Dish", "\"Spareribs\"", "Party Size", "\"small\"")));
        assertThat(pass.passed()).isTrue();
        assertThat(pass.checks().getFirst().matchedRules()).isNotEmpty();
        assertThat(pass.checks().getFirst().table()).isTrue();

        ScenarioResult fail = runner.run(1, new Scenario("fail", inputs, map("Dish", "\"Stew\"")));
        assertThat(fail.passed()).isFalse();
        assertThat(fail.checks().getFirst().actual()).isEqualTo("\"Spareribs\"");

        ScenarioResult unknown = runner.run(2, new Scenario("unknown", map("Weather", "\"sunny\""),
                map("Dessert", "\"cake\"")));
        assertThat(unknown.passed()).isFalse();
        assertThat(unknown.errors()).containsExactly("Input 'Weather' is not in the model");
        assertThat(unknown.checks().getFirst().messages().getFirst().text()).contains("not in the model");

        ScenarioResult numbers = runner.run(3, new Scenario("scale", map("Guest Count", "3.00", "Season", "\"Fall\""),
                map("Party Size", "\"small\"")));
        assertThat(numbers.passed()).isTrue();
    }

    @Test
    void capturesRequestedDecisionsOfAnEvaluation() {
        ModelEvaluator evaluator = new ModelEvaluator(new DmnReader(TestModels.dish()), FEEL);
        Evaluation e = evaluator.evaluate(map("Season", "\"Winter\"", "Guest_Count", " 8 ",
                "Guests_With_Children", ""), List.of("Dish"), null);
        Scenario s = new ScenarioRunner(evaluator, FEEL).capture("winter", e);

        assertThat(s.inputs()).containsExactly(Map.entry("Season", "\"Winter\""), Map.entry("Guest Count", "8"));
        assertThat(s.expected()).containsExactly(Map.entry("Dish", "\"Roastbeef\""));
        assertThat(new ScenarioRunner(evaluator, FEEL).run(0, s).passed()).isTrue();
    }
}
