package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.xml.DmnDocument.attr;

import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnNamespaces;

/** Moves a model to the latest DMN version. */
public final class ModelConverter {

    private final DmnDocument doc;

    ModelConverter(DmnDocument doc) {
        this.doc = doc;
    }

    /** Moves the model to the DMN 1.5 namespaces. Structure is compatible from 1.2; 1.1 is best effort. */
    public void toLatest() {
        String oldNs = doc.ns();
        if (DmnNamespaces.isLatest(oldNs)) {
            return;
        }
        boolean from11 = DmnNamespaces.DMN_1_1.equals(oldNs);
        String oldDi = doc.dmndi().map(Element::getNamespaceURI).orElse(null);
        NodeList all = doc.dom().getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            NamedNodeMap attrs = e.getAttributes();
            for (int k = 0; k < attrs.getLength(); k++) {
                Attr a = (Attr) attrs.item(k);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) {
                    if (a.getValue().equals(oldNs)) {
                        a.setValue(DmnNamespaces.LATEST);
                    } else if (oldDi != null && a.getValue().equals(oldDi)) {
                        a.setValue(DmnNamespaces.DMNDI_1_3);
                    }
                }
            }
        }
        List<Element> snapshot = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            snapshot.add((Element) all.item(i));
        }
        for (Element e : snapshot) {
            if (oldNs.equals(e.getNamespaceURI())) {
                doc.dom().renameNode(e, DmnNamespaces.LATEST, e.getNodeName());
            } else if (oldDi != null && oldDi.equals(e.getNamespaceURI())) {
                doc.dom().renameNode(e, DmnNamespaces.DMNDI_1_3, e.getNodeName());
            }
        }
        if (from11) {
            stripFeelPrefixes(doc.definitions());
        }
        String language = attr(doc.definitions(), "expressionLanguage");
        if (language == null || DmnNamespaces.isFeel(language)) {
            doc.definitions().setAttribute("expressionLanguage", DmnNamespaces.FEEL_1_5);
        }
        String typeLanguage = attr(doc.definitions(), "typeLanguage");
        if (typeLanguage != null && DmnNamespaces.isFeel(typeLanguage)) {
            doc.definitions().setAttribute("typeLanguage", DmnNamespaces.FEEL_1_5);
        }
    }

    /** DMN 1.1 typeRefs are QNames ({@code feel:string}); later versions use plain names. */
    private void stripFeelPrefixes(Element root) {
        NodeList all = root.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.hasAttribute("typeRef")) {
                e.setAttribute("typeRef", unprefix(e, e.getAttribute("typeRef")));
            }
            if ("typeRef".equals(e.getLocalName()) && e.getFirstChild() != null
                    && e.getFirstChild().getNodeType() == Node.TEXT_NODE) {
                e.setTextContent(unprefix(e, e.getTextContent().strip()));
            }
        }
    }

    private String unprefix(Element context, String qname) {
        int colon = qname.indexOf(':');
        if (colon < 0) {
            return qname;
        }
        String ns = context.lookupNamespaceURI(qname.substring(0, colon));
        boolean strip = ns == null || DmnNamespaces.isFeel(ns) || ns.equals(doc.modelNamespace())
                || ns.startsWith("http://www.omg.org/spec/FEEL");
        return strip ? qname.substring(colon + 1) : qname;
    }
}
