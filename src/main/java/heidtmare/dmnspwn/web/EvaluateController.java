package heidtmare.dmnspwn.web;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.InputForms.InputField;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.scenario.Scenario;
import heidtmare.dmnspwn.scenario.ScenarioService;
import heidtmare.dmnspwn.store.ModelService;

import jakarta.servlet.http.HttpSession;

/**
 * Evaluates a model's decisions for input values entered as FEEL expressions, plus an optional ad-hoc FEEL
 * expression. Evaluation has no side effects; the last form values are kept in the session per model.
 */
@Controller
@RequestMapping("/models/{id}/evaluate")
public class EvaluateController {

    static final String INPUT_PREFIX = "in.";

    private final ModelService models;
    private final PageSupport pages;
    private final Feel feel;
    private final ScenarioService scenarios;

    public EvaluateController(ModelService models, Feel feel, PageSupport pages, ScenarioService scenarios) {
        this.models = models;
        this.pages = pages;
        this.feel = feel;
        this.scenarios = scenarios;
    }

    /** Form values remembered between visits. */
    record Saved(Map<String, String> inputs, String decision, String expression) implements Serializable {
    }

    /** Shows the form; {@code test} fills it with the inputs of a saved scenario (by index) and evaluates them. */
    @GetMapping
    public String form(@PathVariable String id, @RequestParam(required = false) String decision,
                       @RequestParam(required = false) Integer test, Model model, HttpSession session) {
        Saved saved = (Saved) session.getAttribute(key(id));
        if (saved == null) {
            saved = new Saved(Map.of(), "", "");
        }
        if (decision != null) {
            saved = new Saved(saved.inputs(), decision, saved.expression());
        }
        DmnReader reader = models.reader(id);
        ModelEvaluator evaluator = new ModelEvaluator(reader, feel);
        Evaluation result = null;
        if (test != null) {
            saved = fromScenario(scenarios.get(id, test), evaluator);
            session.setAttribute(key(id), saved);
            result = evaluate(evaluator, saved);
        }
        page(id, reader, evaluator, saved, result, model);
        return "evaluate";
    }

    @PostMapping
    public String evaluate(@PathVariable String id, @RequestParam Map<String, String> params,
                           @RequestParam(defaultValue = "") String decision,
                           @RequestParam(defaultValue = "") String expression, Model model, HttpSession session) {
        Saved saved = new Saved(inputs(params), decision, expression);
        session.setAttribute(key(id), saved);
        DmnReader reader = models.reader(id);
        ModelEvaluator evaluator = new ModelEvaluator(reader, feel);
        page(id, reader, evaluator, saved, evaluate(evaluator, saved), model);
        return "evaluate";
    }

    /** Input texts keyed by input data id, from the {@code in.<id>} form fields. */
    static Map<String, String> inputs(Map<String, String> params) {
        Map<String, String> inputs = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith(INPUT_PREFIX)) {
                inputs.put(k.substring(INPUT_PREFIX.length()), v);
            }
        });
        return inputs;
    }

    private static Evaluation evaluate(ModelEvaluator evaluator, Saved saved) {
        String decision = saved.decision();
        return evaluator.evaluate(saved.inputs(), decision.isBlank() ? List.of() : List.of(decision),
                saved.expression());
    }

    /** The form values of a scenario: its inputs and, when it checks a single decision, that decision. */
    private static Saved fromScenario(Scenario scenario, ModelEvaluator evaluator) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (InputField f : evaluator.inputFields()) {
            String text = scenario.inputs().get(f.name());
            if (text != null) {
                inputs.put(f.id(), text);
            }
        }
        String decision = "";
        if (scenario.expected().size() == 1) {
            String name = scenario.expected().keySet().iterator().next();
            decision = evaluator.decisions().stream().filter(d -> name.equals(d.name())).map(ElementView::id)
                    .findFirst().orElse("");
        }
        return new Saved(inputs, decision, "");
    }

    private void page(String id, DmnReader reader, ModelEvaluator evaluator, Saved saved, Evaluation result,
                      Model model) {
        pages.common(model, id, reader);
        model.addAttribute("fields", evaluator.inputFields());
        model.addAttribute("decisions", evaluator.decisions());
        Map<String, ExpressionView> logic = new LinkedHashMap<>();
        evaluator.decisions().forEach(d -> logic.put(d.id(), d.logic()));
        model.addAttribute("logic", logic);
        model.addAttribute("saved", saved);
        model.addAttribute("result", result);
    }

    private static String key(String id) {
        return "evaluate:" + id;
    }
}
