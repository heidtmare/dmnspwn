package heidtmare.dmnspwn.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValContext;

import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Evaluation.DecisionResult;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.InputForms.InputField;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.eval.Scope;
import heidtmare.dmnspwn.eval.Trace;
import heidtmare.dmnspwn.eval.Values;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.scenario.TestReport.Check;
import heidtmare.dmnspwn.scenario.TestReport.ScenarioResult;

/** Runs scenarios against one model and captures new scenarios from evaluations. */
public final class ScenarioRunner {

    private final ModelEvaluator evaluator;
    private final Feel feel;
    private final Map<String, String> inputIds = new LinkedHashMap<>();
    private final Map<String, String> inputNames = new LinkedHashMap<>();
    private final Map<String, ElementView> decisions = new LinkedHashMap<>();

    public ScenarioRunner(ModelEvaluator evaluator, Feel feel) {
        this.evaluator = evaluator;
        this.feel = feel;
        for (InputField f : evaluator.inputFields()) {
            if (f.name() != null) {
                inputIds.putIfAbsent(f.name(), f.id());
                inputNames.put(f.id(), f.name());
            }
        }
        evaluator.decisions().forEach(d -> {
            if (d.name() != null) {
                decisions.putIfAbsent(d.name(), d);
            }
        });
    }

    public List<ScenarioResult> runAll(List<Scenario> scenarios) {
        List<ScenarioResult> results = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            results.add(run(i, scenarios.get(i)));
        }
        return results;
    }

    public ScenarioResult run(int index, Scenario scenario) {
        List<String> errors = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        scenario.inputs().forEach((name, text) -> {
            String id = inputIds.get(name);
            if (id == null) {
                errors.add("Input '" + name + "' is not in the model");
            } else {
                texts.put(id, text);
            }
        });
        if (scenario.expected().isEmpty()) {
            errors.add("No expected results");
        }
        List<String> requested = scenario.expected().keySet().stream()
                .filter(decisions::containsKey).map(n -> decisions.get(n).id()).toList();
        Evaluation evaluation = evaluator.evaluate(texts, requested, null);
        evaluation.inputs().stream().filter(i -> i.error() != null)
                .forEach(i -> errors.add("Input '" + i.name() + "': " + i.error()));

        Map<String, DecisionResult> actual = new LinkedHashMap<>();
        evaluation.decisions().forEach(d -> actual.put(d.id(), d));
        List<Check> checks = new ArrayList<>();
        scenario.expected().forEach((name, text) -> checks.add(check(name, text, actual)));
        return new ScenarioResult(index, scenario, errors, checks);
    }

    private Check check(String name, String text, Map<String, DecisionResult> actual) {
        ElementView decision = decisions.get(name);
        if (decision == null) {
            return new Check(name, null, text, "", false,
                    List.of(new Trace.Message(true, "Decision '" + name + "' is not in the model")), List.of(), false);
        }
        DecisionResult result = actual.get(decision.id());
        List<Trace.Message> messages = new ArrayList<>(result.messages());
        Feel.Result expected = feel.evaluate(text == null || text.isBlank() ? "null" : text, Scope.empty());
        boolean passed;
        if (expected.failed() || !expected.warnings().isEmpty()) {
            messages.addFirst(new Trace.Message(true, "Expected value is invalid: "
                    + (expected.failed() ? expected.error() : String.join("; ", expected.warnings()))));
            passed = false;
        } else {
            passed = Values.same(expected.value(), result.value());
        }
        return new Check(name, decision.id(), text, result.formatted(), passed, messages, result.matchedRules(),
                result.table());
    }

    /**
     * A scenario recording an evaluation: its non-empty inputs and the results of the requested decisions, as
     * expected values. Results that a test file cannot hold (functions, errors) are left out.
     */
    public Scenario capture(String name, Evaluation evaluation) {
        Map<String, String> inputs = new LinkedHashMap<>();
        evaluation.inputs().forEach(i -> {
            if (!i.text().isBlank() && inputNames.containsKey(i.id())) {
                inputs.put(inputNames.get(i.id()), i.text().strip());
            }
        });
        Map<String, String> expected = new LinkedHashMap<>();
        evaluation.decisions().stream().filter(DecisionResult::requested)
                .filter(d -> d.name() != null && storable(d.value()))
                .forEach(d -> expected.put(d.name(), d.formatted()));
        return new Scenario(name, inputs, expected);
    }

    /** Whether a value can be written as a TCK test value. */
    static boolean storable(Val v) {
        return switch (Values.typeName(v)) {
            case "list" -> Values.items(v).stream().allMatch(ScenarioRunner::storable);
            case "context" -> Values.entries((ValContext) v).values().stream()
                    .allMatch(ScenarioRunner::storable);
            case "", "function" -> false;
            default -> true;
        };
    }
}
