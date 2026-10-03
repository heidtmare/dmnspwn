package heidtmare.dmnspwn.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.Href;

/** DOM-level view of requirement / association elements, used by the editors. */
final class ConnectionElements {

    static final Set<String> REQUIREMENTS =
            Set.of("informationRequirement", "knowledgeRequirement", "authorityRequirement");

    /** A connector element with its (local) endpoints; {@code sourceId} is null for imported sources. */
    record Conn(Element element, String sourceId, String targetId) {
    }

    private ConnectionElements() {
    }

    static List<Conn> all(DmnDocument doc) {
        String modelNs = doc.modelNamespace();
        List<Conn> result = new ArrayList<>();
        for (Element node : doc.children(doc.definitions())) {
            if (ElementKind.fromLocalName(node.getLocalName()).isEmpty()) {
                continue;
            }
            for (Element req : doc.children(node)) {
                if (REQUIREMENTS.contains(req.getLocalName())) {
                    result.add(new Conn(req, sourceOf(doc, req, modelNs), node.getAttribute("id")));
                }
            }
        }
        for (Element assoc : doc.children(doc.definitions(), "association")) {
            result.add(new Conn(assoc, refOf(doc, assoc, "sourceRef", modelNs), refOf(doc, assoc, "targetRef", modelNs)));
        }
        return result;
    }

    private static String sourceOf(DmnDocument doc, Element req, String modelNs) {
        Optional<Element> ref = doc.children(req).stream().filter(c -> c.hasAttribute("href")).findFirst();
        return ref.map(r -> Href.parse(r.getAttribute("href"))).filter(h -> h.isLocal(modelNs)).map(Href::id)
                .orElse(null);
    }

    private static String refOf(DmnDocument doc, Element assoc, String child, String modelNs) {
        return doc.child(assoc, child).map(r -> Href.parse(r.getAttribute("href"))).filter(h -> h.isLocal(modelNs))
                .map(Href::id).orElse(null);
    }
}
