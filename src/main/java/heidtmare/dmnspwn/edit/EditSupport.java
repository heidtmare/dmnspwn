package heidtmare.dmnspwn.edit;

import java.util.List;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Lookups and checks shared by the editors. */
final class EditSupport {

    private EditSupport() {
    }

    /** A DRG element or text annotation by id. */
    static Element node(DmnDocument doc, String id) {
        return doc.findById(id)
                .filter(e -> e.getParentNode() == doc.definitions())
                .filter(e -> ElementKind.fromLocalName(e.getLocalName()).isPresent())
                .orElseThrow(() -> new DmnEditException("No element with id '" + id + "'"));
    }

    static Element at(List<Element> list, int index) {
        if (index < 0 || index >= list.size()) {
            throw new DmnEditException("Index out of range: " + index);
        }
        return list.get(index);
    }

    /** Inserts {@code child} before the sibling at {@code position}, or appends it when there is none. */
    static void insertAt(DmnDocument doc, Element parent, List<Element> siblings, int position, Element child) {
        if (position >= 0 && position < siblings.size()) {
            parent.insertBefore(child, siblings.get(position));
        } else {
            doc.insert(parent, child);
        }
    }

    static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new DmnEditException(what + " is required");
        }
        return value.strip();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
