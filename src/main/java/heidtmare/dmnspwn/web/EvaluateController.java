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

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.diagram.Overlay;
import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.InputForms.InputField;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.scenario.Scenario;
import heidtmare.dmnspwn.scenario.ScenarioService;
import heidtmare.dmnspwn.scenario.ScenarioRunner.TestReport.Check;
import heidtmare.dmnspwn.scenario.ScenarioRunner.TestReport.ScenarioResult;
import heidtmare.dmnspwn.store.ModelService;

import jakarta.servlet.http.HttpSession;

/**
 * Evaluates a model's decisions for input values entered as FEEL expressions, plus an optional ad-hoc FEEL
 * expression. Evaluation has no side effects; the last form values are kept in the session per model. Results are
 * also drawn on the DRD; when the form is filled from a saved test, the test's checks are shown with them.
 */
@Controller
@RequestMapping("/models/{id}/evaluate")
public class EvaluateController {

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

    /** Shows the form; {@code test} fills it with the inputs of a saved scenario (by index) and evaluates them. */
    @GetMapping
    public String form(@PathVariable String id, @RequestParam(required = false) String decision,
                       @RequestParam(required = false) Integer test, @RequestParam(required = false) String drd,
                       Model model, HttpSession session) {
        EvaluationForm form = session.getAttribute(key(id)) instanceof EvaluationForm f ? f : EvaluationForm.EMPTY;
        if (decision != null) {
            form = form.withDecision(decision);
        }
        DmnReader reader = models.reader(id);
        ModelEvaluator evaluator = new ModelEvaluator(reader, feel);
        Evaluation result = null;
        ScenarioResult testResult = null;
        if (test != null) {
            Scenario scenario = scenarios.get(id, test);
            form = fromScenario(scenario, evaluator);
            session.setAttribute(key(id), form);
            result = form.evaluate(evaluator);
            testResult = scenarios.runner(evaluator).run(test, scenario);
        }
        page(id, reader, evaluator, form, result, testResult, drd, model);
        return "evaluate";
    }

    @PostMapping
    public String evaluate(@PathVariable String id, @RequestParam Map<String, String> params,
                           @RequestParam(defaultValue = "") String decision,
                           @RequestParam(defaultValue = "") String expression,
                           @RequestParam(required = false) String drd,
                           @RequestParam(required = false) String switchDrd, Model model, HttpSession session) {
        if (switchDrd != null) {
            drd = switchDrd;
        }
        EvaluationForm form = EvaluationForm.of(params, decision, expression);
        session.setAttribute(key(id), form);
        DmnReader reader = models.reader(id);
        ModelEvaluator evaluator = new ModelEvaluator(reader, feel);
        page(id, reader, evaluator, form, form.evaluate(evaluator), null, drd, model);
        return "evaluate";
    }

    /** The form values of a scenario: its inputs and, when it checks a single decision, that decision. */
    private static EvaluationForm fromScenario(Scenario scenario, ModelEvaluator evaluator) {
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
        return new EvaluationForm(inputs, decision, "");
    }

    private void page(String id, DmnReader reader, ModelEvaluator evaluator, EvaluationForm form, Evaluation result,
                      ScenarioResult testResult, String drd, Model model) {
        pages.common(model, id, reader);
        model.addAttribute("fields", evaluator.inputFields());
        model.addAttribute("decisions", evaluator.decisions());
        Map<String, ExpressionView> logic = new LinkedHashMap<>();
        evaluator.decisions().forEach(d -> logic.put(d.id(), d.logic()));
        model.addAttribute("logic", logic);
        model.addAttribute("saved", form);
        model.addAttribute("result", result);
        model.addAttribute("diagram", DiagramBuilder.build(reader, drd));
        Map<String, Check> checks = new LinkedHashMap<>();
        if (testResult != null) {
            testResult.checks().stream().filter(c -> c.decisionId() != null).forEach(c -> checks.put(c.decisionId(), c));
        }
        model.addAttribute("testResult", testResult);
        model.addAttribute("checks", checks);
        if (result != null) {
            Map<String, String> failed = new LinkedHashMap<>();
            checks.values().stream().filter(c -> !c.passed()).forEach(c -> failed.put(c.decisionId(), c.expected()));
            model.addAttribute("overlay", Overlay.of(result, failed));
        }
    }

    private static String key(String id) {
        return "evaluate:" + id;
    }

    /**
     * The values of the evaluation form: input texts keyed by input data id, the decision to evaluate (blank: all) and
     * an optional ad-hoc FEEL expression. Kept in the session between visits.
     */
    static record EvaluationForm(Map<String, String> inputs, String decision, String expression) implements Serializable {

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
}
