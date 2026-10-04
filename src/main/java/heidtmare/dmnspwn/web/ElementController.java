package heidtmare.dmnspwn.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.diagram.DiagramView.DiagramRef;
import heidtmare.dmnspwn.diagram.Dmndi;
import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.edit.DmnEditException;
import heidtmare.dmnspwn.edit.DecisionTableEditor;
import heidtmare.dmnspwn.edit.Forms.Action;
import heidtmare.dmnspwn.edit.Forms.DecisionTableForm;
import heidtmare.dmnspwn.edit.Forms.ElementForm;
import heidtmare.dmnspwn.edit.Forms.ParametersForm;
import heidtmare.dmnspwn.edit.Forms.ServiceForm;
import heidtmare.dmnspwn.edit.LogicType;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.HitPolicy;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.store.ModelNotFoundException;
import heidtmare.dmnspwn.store.ModelService;

/** Element details, properties, requirements, layout and decision logic editing. */
@Controller
@RequestMapping("/models/{id}/elements/{elementId}")
public class ElementController {

    private final ModelService models;

    public ElementController(ModelService models) {
        this.models = models;
    }

    /** Shape bounds of the element on one diagram, for the layout form. */
    public record Placement(DiagramRef diagram, Bounds bounds) {
    }

    /** A candidate element for a new requirement, with the connector DMN would create. */
    public record Candidate(String id, String name, ElementKind kind, ConnectionKind connection) {
    }

    @GetMapping
    public String view(@PathVariable String id, @PathVariable String elementId, Model model) {
        DmnReader reader = models.reader(id);
        ElementView element = element(reader, elementId);
        PageSupport.common(model, id, reader, models);
        model.addAttribute("element", element);
        model.addAttribute("elements", reader.elements());
        model.addAttribute("logicTypes", LogicType.available(reader.document().ns()));

        List<Candidate> candidates = new ArrayList<>();
        for (ElementView other : reader.elements()) {
            if (!other.id().equals(elementId)) {
                ConnectionKind.between(other.kind(), element.kind()).ifPresent(
                        k -> candidates.add(new Candidate(other.id(), other.displayName(), other.kind(), k)));
            }
        }
        model.addAttribute("candidates", candidates);
        List<Candidate> dependents = new ArrayList<>();
        for (ElementView other : reader.elements()) {
            if (!other.id().equals(elementId)) {
                ConnectionKind.between(element.kind(), other.kind()).ifPresent(
                        k -> dependents.add(new Candidate(other.id(), other.displayName(), other.kind(), k)));
            }
        }
        model.addAttribute("dependents", dependents);

        List<Placement> placements = new ArrayList<>();
        for (DiagramRef d : DiagramBuilder.diagrams(reader.document())) {
            Dmndi.shapeBounds(reader.document(), d.id(), elementId).ifPresent(b -> placements.add(new Placement(d, b)));
        }
        model.addAttribute("placements", placements);
        return "element";
    }

    @PostMapping
    public String update(@PathVariable String id, @PathVariable String elementId,
                         @ModelAttribute ElementForm form, RedirectAttributes flash) {
        models.edit(id, ed -> ed.updateElement(elementId, form));
        flash.addFlashAttribute("success", "Saved");
        return back(id, elementId);
    }

    @PostMapping("/delete")
    public String delete(@PathVariable String id, @PathVariable String elementId, RedirectAttributes flash) {
        models.edit(id, ed -> ed.deleteElement(elementId));
        flash.addFlashAttribute("success", "Element deleted");
        return "redirect:/models/" + id;
    }

    /** Moves/resizes the element's shape; called by the diagram drag script and the layout form. */
    @PostMapping("/bounds")
    public String bounds(@PathVariable String id, @PathVariable String elementId,
                         @RequestParam(required = false) String drd, @RequestParam double x, @RequestParam double y,
                         @RequestParam(required = false) Double width, @RequestParam(required = false) Double height,
                         RedirectAttributes flash) {
        models.edit(id, ed -> ed.diagrams().move(drd, elementId, x, y, width, height));
        flash.addFlashAttribute("success", "Layout saved");
        return back(id, elementId);
    }

    @PostMapping("/service")
    public String service(@PathVariable String id, @PathVariable String elementId,
                          @ModelAttribute ServiceForm form, RedirectAttributes flash) {
        models.edit(id, ed -> ed.updateService(elementId, form));
        flash.addFlashAttribute("success", "Decision service saved");
        return back(id, elementId);
    }

    @PostMapping("/parameters")
    public String parameters(@PathVariable String id, @PathVariable String elementId,
                             @ModelAttribute ParametersForm form, RedirectAttributes flash) {
        models.edit(id, ed -> ed.logic().saveParameters(elementId, form));
        flash.addFlashAttribute("success", "Parameters saved");
        return back(id, elementId) + "#parameters";
    }

    // ---- logic ---------------------------------------------------------------------------------

    @PostMapping("/logic/type")
    public String logicType(@PathVariable String id, @PathVariable String elementId, @RequestParam LogicType type,
                            RedirectAttributes flash) {
        models.edit(id, ed -> ed.logic().setType(elementId, type));
        flash.addFlashAttribute("success", type == LogicType.NONE ? "Logic removed" : type.displayName() + " created");
        return type == LogicType.NONE ? back(id, elementId) : "redirect:/models/" + id + "/elements/" + elementId + "/logic";
    }

    @GetMapping("/logic")
    public String logic(@PathVariable String id, @PathVariable String elementId,
                        @RequestParam(defaultValue = "false") boolean raw, Model model) {
        DmnReader reader = models.reader(id);
        ElementView element = element(reader, elementId);
        if (!element.kind().hasLogic()) {
            throw new DmnEditException(element.kind().displayName() + " has no decision logic");
        }
        PageSupport.common(model, id, reader, models);
        model.addAttribute("element", element);
        model.addAttribute("logicTypes", LogicType.available(reader.document().ns()));
        ExpressionView logic = element.logic();
        if (!raw && logic instanceof ExpressionView.DecisionTable table) {
            model.addAttribute("table", table);
            model.addAttribute("hitPolicies", HitPolicy.values());
            model.addAttribute("aggregations", DecisionTableEditor.AGGREGATIONS);
            return "logic-table";
        }
        if (!raw && logic instanceof ExpressionView.Literal literal) {
            model.addAttribute("literal", literal);
            return "logic-literal";
        }
        model.addAttribute("xml", reader.logicXml(elementId).orElse(""));
        return "logic-xml";
    }

    @PostMapping("/logic/decision-table")
    public String saveTable(@PathVariable String id, @PathVariable String elementId,
                            @ModelAttribute DecisionTableForm form, RedirectAttributes flash) {
        models.edit(id, ed -> ed.tables().save(elementId, form));
        if (Action.isSave(form.getAction())) {
            flash.addFlashAttribute("success", "Decision table saved");
        }
        return "redirect:/models/" + id + "/elements/" + elementId + "/logic";
    }

    @PostMapping("/logic/literal")
    public String saveLiteral(@PathVariable String id, @PathVariable String elementId,
                              @RequestParam(defaultValue = "") String text,
                              @RequestParam(required = false) String typeRef, RedirectAttributes flash) {
        models.edit(id, ed -> ed.logic().saveLiteral(elementId, text, typeRef));
        flash.addFlashAttribute("success", "Expression saved");
        return "redirect:/models/" + id + "/elements/" + elementId + "/logic";
    }

    @PostMapping("/logic/xml")
    public String saveXml(@PathVariable String id, @PathVariable String elementId, @RequestParam String xml,
                          RedirectAttributes flash) {
        models.edit(id, ed -> ed.logic().replaceXml(elementId, xml));
        flash.addFlashAttribute("success", "Expression saved");
        return "redirect:/models/" + id + "/elements/" + elementId + "/logic?raw=true";
    }

    private static ElementView element(DmnReader reader, String elementId) {
        return reader.element(elementId).orElseThrow(() -> new ModelNotFoundException(elementId));
    }

    private static String back(String id, String elementId) {
        return "redirect:/models/" + id + "/elements/" + elementId;
    }
}
