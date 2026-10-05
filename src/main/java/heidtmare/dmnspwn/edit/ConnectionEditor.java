package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.edit.EditSupport.node;

import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.model.ConnectionElement;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.RequirementGraph;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnNamespaces;
import heidtmare.dmnspwn.xml.Href;

/** Adds and removes requirements and associations, keeping diagram edges in step. */
public final class ConnectionEditor {

    private final DmnDocument doc;
    private final DiagramEditor diagrams;

    ConnectionEditor(DmnDocument doc, DiagramEditor diagrams) {
        this.doc = doc;
        this.diagrams = diagrams;
    }

    /** Connects two elements with the connection DMN prescribes for their kinds, and returns that kind. */
    public ConnectionKind connect(String sourceId, String targetId) {
        if (sourceId == null || sourceId.equals(targetId)) {
            throw new DmnEditException("Choose two different elements to connect");
        }
        Element source = node(doc, sourceId);
        Element target = node(doc, targetId);
        ElementKind sk = DmnReader.kindOf(source);
        ElementKind tk = DmnReader.kindOf(target);
        ConnectionKind kind = ConnectionKind.between(sk, tk).orElseThrow(() -> new DmnEditException(
                "DMN does not allow a connection from a %s to a %s".formatted(sk.displayName(), tk.displayName())));

        List<ConnectionElement> existing = ConnectionElement.all(doc);
        if (existing.stream().anyMatch(c -> c.kind() == kind && c.connects(sourceId, targetId))) {
            throw new DmnEditException("These elements are already connected");
        }

        if (kind == ConnectionKind.ASSOCIATION) {
            Element assoc = doc.createWithId(kind.localName(), "Association");
            Element src = doc.create("sourceRef");
            src.setAttribute("href", Href.local(sourceId));
            Element tgt = doc.create("targetRef");
            tgt.setAttribute("href", Href.local(targetId));
            assoc.appendChild(src);
            assoc.appendChild(tgt);
            doc.insert(doc.definitions(), assoc);
        } else {
            if (new RequirementGraph(existing).dependsOn(sourceId, targetId)) {
                throw new DmnEditException("That connection would create a cycle in the requirement graph");
            }
            Element req = doc.create(kind.localName());
            if (!DmnNamespaces.DMN_1_1.equals(doc.ns())) {
                req.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix(kind.localName())));
            }
            Element ref = doc.create(kind.referenceElement(sk));
            ref.setAttribute("href", Href.local(sourceId));
            req.appendChild(ref);
            doc.insert(target, req);
        }
        if (doc.supportsDmndi()) {
            diagrams.addEdgesForNewConnection();
        }
        return kind;
    }

    /** Removes a connection by its {@link ConnectionElement#ref() reference}. */
    public void disconnect(String ref) {
        ConnectionElement conn = ConnectionElement.all(doc).stream().filter(c -> c.ref().equals(ref)).findFirst()
                .orElseThrow(() -> new DmnEditException("Connection not found"));
        DmnDocument.remove(conn.element());
        if (conn.id() != null) {
            diagrams.purge(null, Set.of(conn.id()));
        }
    }
}
