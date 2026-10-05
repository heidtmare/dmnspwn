package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.edit.EditSupport.at;
import static heidtmare.dmnspwn.edit.EditSupport.required;
import static heidtmare.dmnspwn.xml.DmnDocument.setAttr;

import java.util.List;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.edit.Forms.Action;
import heidtmare.dmnspwn.edit.Forms.ComponentCommand;
import heidtmare.dmnspwn.edit.Forms.ComponentRow;
import heidtmare.dmnspwn.edit.Forms.ItemDefinitionForm;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Edits item definitions (data types), addressed by index path such as {@code 2} or {@code 2.0}. */
public final class ItemDefinitionEditor {

    private final DmnDocument doc;

    ItemDefinitionEditor(DmnDocument doc) {
        this.doc = doc;
    }

    /** Adds a top-level item definition and returns its path. */
    public String add(String name, String typeRef, boolean collection) {
        Element item = doc.createWithId("itemDefinition", "ItemDefinition");
        item.setAttribute("name", required(name, "Type name"));
        if (collection) {
            item.setAttribute("isCollection", "true");
        }
        doc.setChildContent(item, "typeRef", typeRef);
        doc.insert(doc.definitions(), item);
        return String.valueOf(doc.children(doc.definitions(), "itemDefinition").size() - 1);
    }

    public void save(String path, ItemDefinitionForm f) {
        Element item = byPath(path);
        Action<ComponentCommand> action = Action.parse(f.getAction(), ComponentCommand.class);
        item.setAttribute("name", required(f.getName(), "Type name"));
        setAttr(item, "label", f.getLabel());
        setCollection(item, f.isCollection());
        doc.setChildContent(item, "typeRef", f.getTypeRef());
        doc.setChildText(item, "allowedValues", f.getAllowedValues());

        List<Element> components = doc.children(item, "itemComponent");
        for (int i = 0; i < Math.min(components.size(), f.getComponents().size()); i++) {
            ComponentRow row = f.getComponents().get(i);
            Element c = components.get(i);
            c.setAttribute("name", required(row.getName(), "Component name"));
            setCollection(c, row.isCollection());
            if (doc.children(c, "itemComponent").isEmpty()) {
                doc.setChildContent(c, "typeRef", row.getTypeRef());
            }
            doc.setChildText(c, "allowedValues", row.getAllowedValues());
        }
        int i = action.row();
        switch (action.command()) {
            case SAVE -> {
            }
            case ADD_COMPONENT -> {
                Element c = doc.createWithId("itemComponent", "ItemComponent");
                c.setAttribute("name", "field" + (components.size() + 1));
                doc.setChildContent(c, "typeRef", "string");
                doc.insert(item, c);
                doc.child(item, "typeRef").ifPresent(DmnDocument::remove);
            }
            case DELETE_COMPONENT -> DmnDocument.remove(at(components, i));
            case MOVE_COMPONENT_UP -> {
                if (i > 0) {
                    item.insertBefore(at(components, i), components.get(i - 1));
                }
            }
        }
    }

    public void delete(String path) {
        DmnDocument.remove(byPath(path));
    }

    public Element byPath(String path) {
        Element current = null;
        try {
            for (String part : path.split("\\.")) {
                List<Element> items = current == null
                        ? doc.children(doc.definitions(), "itemDefinition")
                        : doc.children(current, "itemComponent");
                current = items.get(Integer.parseInt(part));
            }
        } catch (RuntimeException e) {
            current = null;
        }
        if (current == null) {
            throw new DmnEditException("Unknown data type " + path);
        }
        return current;
    }

    private static void setCollection(Element item, boolean collection) {
        if (collection) {
            item.setAttribute("isCollection", "true");
        } else {
            item.removeAttribute("isCollection");
        }
    }
}
