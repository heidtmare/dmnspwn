package heidtmare.dmnspwn.web;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.ModelEvaluator;

/**
 * The values of the evaluation form: input texts keyed by input data id, the decision to evaluate (blank: all) and
 * an optional ad-hoc FEEL expression. Kept in the session between visits.
 */
record EvaluationForm(Map<String, String> inputs, String decision, String expression) implements Serializable {

    static final String INPUT_PREFIX = "in.";
    static final EvaluationForm EMPTY = new EvaluationForm(Map.of(), "", "");

    /** The form as posted: inputs come from the {@code in.<id>} fields. */
    static EvaluationForm of(Map<String, String> params, String decision, String expression) {
        Map<String, String> inputs = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith(INPUT_PREFIX)) {
                inputs.put(k.substring(INPUT_PREFIX.length()), v);
            }
        });
        return new EvaluationForm(inputs, decision, expression);
    }

    EvaluationForm withDecision(String decision) {
        return new EvaluationForm(inputs, decision, expression);
    }

    /** The decision ids to evaluate; empty for all. */
    List<String> requested() {
        return decision.isBlank() ? List.of() : List.of(decision);
    }

    Evaluation evaluate(ModelEvaluator evaluator) {
        return evaluator.evaluate(inputs, requested(), expression);
    }
}
