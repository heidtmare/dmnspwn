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
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ExpressionView;
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
    private final Feel feel;

    public EvaluateController(ModelService models, Feel feel) {
        this.models = models;
        this.feel = feel;
    }

    /** Form values remembered between visits. */
    record Saved(Map<String, String> inputs, String decision, String expression) implements Serializable {
    }

    @GetMapping
    public String form(@PathVariable String id, @RequestParam(required = false) String decision, Model model,
                       HttpSession session) {
        Saved saved = (Saved) session.getAttribute(key(id));
        if (saved == null) {
            saved = new Saved(Map.of(), "", "");
        }
        if (decision != null) {
            saved = new Saved(saved.inputs(), decision, saved.expression());
        }
        DmnReader reader = models.reader(id);
        page(id, reader, new ModelEvaluator(reader, feel), saved, null, model);
        return "evaluate";
    }

    @PostMapping
    public String evaluate(@PathVariable String id, @RequestParam Map<String, String> params,
                           @RequestParam(defaultValue = "") String decision,
                           @RequestParam(defaultValue = "") String expression, Model model, HttpSession session) {
        Map<String, String> inputs = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith(INPUT_PREFIX)) {
                inputs.put(k.substring(INPUT_PREFIX.length()), v);
            }
        });
        Saved saved = new Saved(inputs, decision, expression);
        session.setAttribute(key(id), saved);
        DmnReader reader = models.reader(id);
        ModelEvaluator evaluator = new ModelEvaluator(reader, feel);
        Evaluation result = evaluator.evaluate(inputs, decision.isBlank() ? List.of() : List.of(decision), expression);
        page(id, reader, evaluator, saved, result, model);
        return "evaluate";
    }

    private void page(String id, DmnReader reader, ModelEvaluator evaluator, Saved saved, Evaluation result,
                      Model model) {
        PageSupport.common(model, id, reader, models);
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
