package heidtmare.dmnspwn.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import heidtmare.dmnspwn.eval.Evaluation;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.scenario.InvalidScenarioException;
import heidtmare.dmnspwn.scenario.Scenario;
import heidtmare.dmnspwn.scenario.ScenarioService;
import heidtmare.dmnspwn.store.ModelService;

/** A model's saved test scenarios: results, saving from the evaluator, accepting results, TCK import/export. */
@Controller
@RequestMapping("/models/{id}/tests")
public class ScenarioController {

    private final ModelService models;
    private final PageSupport pages;
    private final ScenarioService scenarios;
    private final Feel feel;

    public ScenarioController(ModelService models, PageSupport pages, ScenarioService scenarios, Feel feel) {
        this.models = models;
        this.pages = pages;
        this.scenarios = scenarios;
        this.feel = feel;
    }

    @GetMapping
    public String list(@PathVariable String id, Model model) {
        DmnReader reader = models.reader(id);
        pages.common(model, id, reader);
        Map<String, ExpressionView> logic = new LinkedHashMap<>();
        reader.elements().forEach(e -> logic.put(e.id(), e.logic()));
        model.addAttribute("logic", logic);
        return "tests";
    }

    /** Saves the evaluator's inputs and current results of the selected decision(s) as a scenario. */
    @PostMapping
    public String save(@PathVariable String id, @RequestParam Map<String, String> params,
                       @RequestParam(defaultValue = "") String decision, @RequestParam(defaultValue = "") String name,
                       RedirectAttributes flash) {
        ModelEvaluator evaluator = new ModelEvaluator(models.reader(id), feel);
        Evaluation result = EvaluationForm.of(params, decision, null).evaluate(evaluator);
        if (result.hasInputErrors()) {
            throw new InvalidScenarioException("Fix the input values before saving them as a test");
        }
        Scenario scenario = scenarios.runner(evaluator).capture(name, result);
        if (scenario.expected().isEmpty()) {
            throw new InvalidScenarioException("There are no decision results to save");
        }
        boolean replaced = scenarios.save(id, scenario);
        flash.addFlashAttribute("success", replaced ? "Test '" + scenario.name() + "' updated" : "Test saved");
        return "redirect:/models/" + id + "/tests";
    }

    @PostMapping("/{index}/accept")
    public String accept(@PathVariable String id, @PathVariable int index, RedirectAttributes flash) {
        Scenario s = scenarios.accept(id, index);
        flash.addFlashAttribute("success", "Current results accepted as expected for '" + s.name() + "'");
        return "redirect:/models/" + id + "/tests";
    }

    @PostMapping("/{index}/delete")
    public String delete(@PathVariable String id, @PathVariable int index, RedirectAttributes flash) {
        scenarios.delete(id, index);
        flash.addFlashAttribute("success", "Test deleted");
        return "redirect:/models/" + id + "/tests";
    }

    @PostMapping("/import")
    public String importFile(@PathVariable String id, @RequestParam MultipartFile file, RedirectAttributes flash)
            throws IOException {
        int count = scenarios.importXml(id, new String(file.getBytes(), StandardCharsets.UTF_8));
        flash.addFlashAttribute("success", count == 1 ? "1 test imported" : count + " tests imported");
        return "redirect:/models/" + id + "/tests";
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(id + ".tests.xml").build().toString())
                .contentType(MediaType.APPLICATION_XML)
                .body(scenarios.xml(id).getBytes(StandardCharsets.UTF_8));
    }
}
