package heidtmare.dmnspwn.web;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.diagram.DiagramView;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Model overview (DRD), model-level edits, source view and export. */
@Controller
@RequestMapping("/models/{id}")
public class ModelController {

    private final ModelService models;
    private final TemplateEngine templates;

    public ModelController(ModelService models, TemplateEngine templates) {
        this.models = models;
        this.templates = templates;
    }

    @GetMapping
    public String view(@PathVariable String id, @RequestParam(required = false) String drd,
                       @RequestParam(defaultValue = "false") boolean actual, Model model) {
        DmnReader reader = models.reader(id);
        PageSupport.common(model, id, reader, models);
        DiagramView diagram = DiagramBuilder.build(reader, drd);
        List<ElementView> elements = reader.elements();
        Set<String> placed = new HashSet<>();
        diagram.nodes().forEach(n -> placed.add(n.elementId()));
        model.addAttribute("diagram", diagram);
        model.addAttribute("actualSize", actual);
        model.addAttribute("elements", elements);
        model.addAttribute("offDiagram", elements.stream().filter(e -> !placed.contains(e.id())).toList());
        model.addAttribute("connections", reader.connections());
        model.addAttribute("imports", reader.imports());
        model.addAttribute("kinds", ElementKind.values());
        return "model";
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(id + ".dmn").build().toString())
                .contentType(MediaType.APPLICATION_XML)
                .body(models.xml(id).getBytes(StandardCharsets.UTF_8));
    }

    /** Standalone SVG export of a DRD, rendered with the same template as the page. */
    @GetMapping(value = "/diagram.svg")
    public ResponseEntity<String> svg(@PathVariable String id, @RequestParam(required = false) String drd) {
        DiagramView diagram = DiagramBuilder.build(models.reader(id), drd);
        Context ctx = new Context();
        ctx.setVariables(Map.of("diagram", diagram, "editable", false));
        String svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + templates.process("diagram-svg", ctx).strip();
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(id + ".svg").build().toString())
                .body(svg);
    }

    @PostMapping("/definitions")
    public String definitions(@PathVariable String id, @RequestParam String name, @RequestParam String namespace,
                              @RequestParam(required = false) String description, RedirectAttributes flash) {
        models.update(id, ed -> {
            ed.updateDefinitions(name, namespace, description);
            return null;
        });
        flash.addFlashAttribute("success", "Model properties saved");
        return "redirect:/models/" + id;
    }

    @PostMapping("/elements")
    public String addElement(@PathVariable String id, @RequestParam ElementKind kind, @RequestParam String name,
                             @RequestParam(required = false) String drd, RedirectAttributes flash) {
        models.update(id, ed -> ed.addElement(kind, name, drd));
        flash.addFlashAttribute("success", kind.displayName() + " '" + name.strip() + "' added");
        return redirect(id, drd);
    }

    @PostMapping("/connections")
    public String connect(@PathVariable String id, @RequestParam String source, @RequestParam String target,
                          @RequestParam(required = false) String drd, @RequestParam(required = false) String back,
                          RedirectAttributes flash) {
        ConnectionKind kind = models.update(id, ed -> ed.connect(source, target));
        flash.addFlashAttribute("success", kind.displayName() + " added");
        return back != null ? "redirect:" + WebAdvice.safeLocalPath(back, "/models/" + id) : redirect(id, drd);
    }

    @PostMapping("/connections/delete")
    public String disconnect(@PathVariable String id, @RequestParam String ref,
                             @RequestParam(required = false) String back, RedirectAttributes flash) {
        models.update(id, ed -> {
            ed.disconnect(ref);
            return null;
        });
        flash.addFlashAttribute("success", "Connection removed");
        return "redirect:" + WebAdvice.safeLocalPath(back, "/models/" + id);
    }

    @PostMapping("/diagram/layout")
    public String layout(@PathVariable String id, @RequestParam(required = false) String drd,
                         RedirectAttributes flash) {
        String diagramId = models.update(id, ed -> {
            var diagram = ed.diagrams().ensureDiagram(drd);
            ed.diagrams().resetLayout(diagram);
            return diagram.getAttribute("id");
        });
        flash.addFlashAttribute("success", "Diagram re-laid out");
        return redirect(id, diagramId);
    }

    @PostMapping("/diagram/add")
    public String addToDiagram(@PathVariable String id, @RequestParam String elementId,
                               @RequestParam(required = false) String drd) {
        String diagramId = models.update(id, ed -> {
            var diagram = ed.diagrams().ensureDiagram(drd);
            ed.diagrams().addShape(diagram, elementId, DmnReader.kindOf(ed.node(elementId)), null);
            return diagram.getAttribute("id");
        });
        return redirect(id, diagramId);
    }

    @PostMapping("/diagram/remove")
    public String removeFromDiagram(@PathVariable String id, @RequestParam String elementId,
                                    @RequestParam(required = false) String drd) {
        String diagramId = models.update(id, ed -> {
            var diagram = ed.diagrams().ensureDiagram(drd);
            ed.diagrams().removeShape(diagram, elementId);
            return diagram.getAttribute("id");
        });
        return redirect(id, diagramId);
    }

    @PostMapping("/undo")
    public String undo(@PathVariable String id, @RequestParam(required = false) String back,
                       RedirectAttributes flash) {
        boolean undone = models.undo(id);
        flash.addFlashAttribute(undone ? "success" : "error", undone ? "Last change undone" : "Nothing to undo");
        return "redirect:" + WebAdvice.safeLocalPath(back, "/models/" + id);
    }

    @PostMapping("/delete")
    public String delete(@PathVariable String id, RedirectAttributes flash) {
        models.delete(id);
        flash.addFlashAttribute("success", "Model deleted");
        return "redirect:/";
    }

    @PostMapping("/convert")
    public String convert(@PathVariable String id, RedirectAttributes flash) {
        models.update(id, ed -> {
            ed.convertToLatest();
            return null;
        });
        flash.addFlashAttribute("success", "Model converted to DMN 1.5");
        return "redirect:/models/" + id;
    }

    @GetMapping("/source")
    public String source(@PathVariable String id, Model model) {
        String xml = models.xml(id);
        DmnReader reader = new DmnReader(DmnDocument.parse(xml));
        PageSupport.common(model, id, reader, models);
        model.addAttribute("xml", xml);
        return "source";
    }

    @PostMapping("/source")
    public String saveSource(@PathVariable String id, @RequestParam String xml, RedirectAttributes flash) {
        models.replaceSource(id, xml);
        flash.addFlashAttribute("success", "Source saved");
        return "redirect:/models/" + id + "/source";
    }

    @GetMapping("/validation")
    public String validation(@PathVariable String id, Model model) {
        DmnReader reader = models.reader(id);
        PageSupport.common(model, id, reader, models);
        return "validation";
    }

    private static String redirect(String id, String drd) {
        return "redirect:" + UriComponentsBuilder.fromPath("/models/{id}").queryParamIfPresent("drd",
                Optional.ofNullable(drd).filter(d -> !d.isBlank())).buildAndExpand(id).encode().toUriString();
    }
}
