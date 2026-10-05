package heidtmare.dmnspwn.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.model.BuiltInTypes;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;
import heidtmare.dmnspwn.scenario.ScenarioService;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.validate.DmnValidator;
import heidtmare.dmnspwn.validate.DmnValidator.Issue;

/** Model attributes shared by every page that belongs to one model. */
@Component
final class PageSupport {

    private final ModelService models;
    private final ScenarioService scenarios;

    PageSupport(ModelService models, ScenarioService scenarios) {
        this.models = models;
        this.scenarios = scenarios;
    }

    void common(Model model, String id, DmnReader reader) {
        model.addAttribute("modelId", id);
        model.addAttribute("info", reader.info());
        model.addAttribute("canUndo", models.canUndo(id));
        model.addAttribute("diagrams", DiagramBuilder.diagrams(reader.document()));
        List<Issue> issues = DmnValidator.validate(reader);
        model.addAttribute("issues", issues);
        model.addAttribute("errorCount", issues.stream().filter(Issue::isError).count());
        model.addAttribute("warningCount", issues.stream().filter(i -> !i.isError()).count());
        model.addAttribute("tests", scenarios.report(id));
        model.addAttribute("typeOptions", typeOptions(reader));
        model.addAttribute("modelUrl", model.getAttribute("contextPath") + "/models/" + id);
        model.addAttribute("linkBase", model.getAttribute("contextPath") + "/models/" + id + "/elements/");
    }

    static List<String> typeOptions(DmnReader reader) {
        Set<String> options = new LinkedHashSet<>();
        collect(reader.itemDefinitions(), options);
        options.addAll(BuiltInTypes.OFFERED);
        return new ArrayList<>(options);
    }

    private static void collect(List<ItemDefinitionView> items, Set<String> options) {
        for (ItemDefinitionView item : items) {
            if (item.name() != null && !item.name().isBlank()) {
                options.add(item.name());
            }
        }
    }
}
