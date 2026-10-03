package heidtmare.dmnspwn.model;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.Href;

/**
 * A requirement or association element of a model with its endpoints, as found in the DOM.
 *
 * @param source         the required element (association: {@code sourceRef}); null when a requirement has no reference
 * @param target         the requiring element (association: {@code targetRef})
 * @param modelNamespace the model's namespace, deciding which references are local
 */
public record ConnectionElement(Element element, ConnectionKind kind, Href source, Href target,
                                String modelNamespace) {

    private static final String SEPARATOR = "|";

    /** Every requirement (of DRG elements) and association in the document, in document order. */
    public static List<ConnectionElement> all(DmnDocument doc) {
        String modelNs = doc.modelNamespace();
        List<ConnectionElement> result = new ArrayList<>();
        for (Element node : doc.children(doc.definitions())) {
            if (ElementKind.fromLocalName(node.getLocalName()).isEmpty()) {
                continue;
            }
            Href target = new Href("", node.getAttribute("id"));
            for (Element req : doc.children(node)) {
                ConnectionKind kind = ConnectionKind.fromLocalName(req.getLocalName())
                        .filter(ConnectionKind::isRequirement).orElse(null);
                if (kind != null) {
                    Href source = doc.children(req).stream().filter(c -> c.hasAttribute("href")).findFirst()
                            .map(r -> Href.parse(r.getAttribute("href"))).orElse(null);
                    result.add(new ConnectionElement(req, kind, source, target, modelNs));
                }
            }
        }
        for (Element assoc : doc.children(doc.definitions(), ConnectionKind.ASSOCIATION.localName())) {
            result.add(new ConnectionElement(assoc, ConnectionKind.ASSOCIATION, ref(doc, assoc, "sourceRef"),
                    ref(doc, assoc, "targetRef"), modelNs));
        }
        return result;
    }

    private static Href ref(DmnDocument doc, Element assoc, String child) {
        return Href.parse(doc.child(assoc, child).map(e -> e.getAttribute("href")).orElse(""));
    }

    /** The element's id, or null when it has none. */
    public String id() {
        String id = element.getAttribute("id");
        return id.isEmpty() ? null : id;
    }

    /**
     * Identifies the connection for removal: its id, or {@code target|kind|source} for a requirement without one
     * ({@code association|source|target} for an association).
     */
    public String ref() {
        String id = id();
        if (id != null) {
            return id;
        }
        String sourceRef = source == null ? "" : source.id();
        return kind == ConnectionKind.ASSOCIATION
                ? String.join(SEPARATOR, kind.localName(), sourceRef, target.id())
                : String.join(SEPARATOR, target.id(), kind.localName(), sourceRef);
    }

    public boolean sourceLocal() {
        return source != null && source.isLocal(modelNamespace);
    }

    /** The id of the source element in this model, or null when it is imported or missing. */
    public String sourceId() {
        return sourceLocal() ? source.id() : null;
    }

    /** The id of the target element in this model, or null when it is imported. */
    public String targetId() {
        return target.isLocal(modelNamespace) ? target.id() : null;
    }

    /** Whether the connection is between the two (local) elements, in either direction for associations. */
    public boolean connects(String sourceId, String targetId) {
        return sourceId.equals(sourceId()) && targetId.equals(targetId())
                || kind == ConnectionKind.ASSOCIATION && sourceId.equals(targetId()) && targetId.equals(sourceId());
    }

    public boolean touches(String elementId) {
        return elementId.equals(sourceId()) || elementId.equals(targetId());
    }
}
