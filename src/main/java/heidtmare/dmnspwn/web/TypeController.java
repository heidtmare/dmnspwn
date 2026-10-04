package heidtmare.dmnspwn.web;

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

import heidtmare.dmnspwn.edit.Forms.Action;
import heidtmare.dmnspwn.edit.Forms.ItemDefinitionForm;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.store.NotFoundException;

/** Item definitions (data types). Types are addressed by index path, e.g. {@code 2} or {@code 2.0}. */
@Controller
@RequestMapping("/models/{id}/types")
public class TypeController {

    private final ModelService models;
    private final PageSupport pages;

    public TypeController(ModelService models, PageSupport pages) {
        this.models = models;
        this.pages = pages;
    }

    @GetMapping
    public String list(@PathVariable String id, Model model) {
        DmnReader reader = models.reader(id);
        pages.common(model, id, reader);
        model.addAttribute("types", reader.itemDefinitions());
        return "types";
    }

    @PostMapping
    public String add(@PathVariable String id, @RequestParam String name,
                      @RequestParam(required = false) String typeRef,
                      @RequestParam(defaultValue = "false") boolean collection, RedirectAttributes flash) {
        String path = models.update(id, ed -> ed.types().add(name, typeRef, collection));
        flash.addFlashAttribute("success", "Data type '" + name.strip() + "' added");
        return "redirect:/models/" + id + "/types/" + path;
    }

    @GetMapping("/{path}")
    public String edit(@PathVariable String id, @PathVariable String path, Model model) {
        DmnReader reader = models.reader(id);
        pages.common(model, id, reader);
        model.addAttribute("type", find(reader.itemDefinitions(), path));
        model.addAttribute("path", path);
        return "type";
    }

    @PostMapping("/{path}")
    public String save(@PathVariable String id, @PathVariable String path, @ModelAttribute ItemDefinitionForm form,
                       RedirectAttributes flash) {
        models.edit(id, ed -> ed.types().save(path, form));
        if (Action.isSave(form.getAction())) {
            flash.addFlashAttribute("success", "Data type saved");
        }
        return "redirect:/models/" + id + "/types/" + path;
    }

    @PostMapping("/{path}/delete")
    public String delete(@PathVariable String id, @PathVariable String path, RedirectAttributes flash) {
        models.edit(id, ed -> ed.types().delete(path));
        flash.addFlashAttribute("success", "Data type deleted");
        int dot = path.lastIndexOf('.');
        return "redirect:/models/" + id + "/types" + (dot < 0 ? "" : "/" + path.substring(0, dot));
    }

    private static ItemDefinitionView find(List<ItemDefinitionView> items, String path) {
        for (ItemDefinitionView item : items) {
            if (item.path().equals(path)) {
                return item;
            }
            if (path.startsWith(item.path() + ".")) {
                return find(item.components(), path);
            }
        }
        throw new NotFoundException("Data type '" + path + "' not found");
    }
}
