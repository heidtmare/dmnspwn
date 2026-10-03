package heidtmare.dmnspwn.xml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.xml.XMLConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A DMN model backed by its DOM. The DOM is the source of truth, so content
 * the editor does not understand (extensions, vendor attributes, comments)
 * survives a round trip untouched.
 */
public final class DmnDocument {

    private final Document dom;
    private final Element definitions;
    /** Ids in use, collected on the first {@link #uniqueId} call and extended with every id it hands out. */
    private Set<String> ids;

    public DmnDocument(Document dom) {
        Element root = dom.getDocumentElement();
        if (root == null || !"definitions".equals(root.getLocalName())
                || !DmnNamespaces.isModel(root.getNamespaceURI())) {
            throw new DmnFormatException(
                    "Not a DMN model: the root element must be <definitions> in an OMG DMN namespace");
        }
        this.dom = dom;
        this.definitions = root;
    }

    public static DmnDocument parse(String xml) {
        return new DmnDocument(DmnXml.parse(xml));
    }

    /** Creates an empty DMN 1.5 model with one empty diagram. */
    public static DmnDocument blank(String name, String namespace) {
        Document dom = DmnXml.newDocument();
        Element root = dom.createElementNS(DmnNamespaces.LATEST, "definitions");
        dom.appendChild(root);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", DmnNamespaces.LATEST);
        DmnDocument doc = new DmnDocument(dom);
        root.setAttribute("id", doc.uniqueId("Definitions"));
        root.setAttribute("name", name);
        root.setAttribute("namespace", namespace);
        root.setAttribute("expressionLanguage", DmnNamespaces.FEEL_1_5);
        root.setAttribute("typeLanguage", DmnNamespaces.FEEL_1_5);
        root.setAttribute("exporter", "dmnspwn");
        Element diagram = doc.createDi(DmnNamespaces.DMNDI_1_3, "DMNDiagram");
        diagram.setAttribute("id", doc.uniqueId("DRD"));
        diagram.setAttribute("name", "Decision Requirements Diagram");
        doc.dmndiOrCreate().appendChild(diagram);
        return doc;
    }

    public String toXml() {
        return DmnXml.serialize(dom);
    }

    public Document dom() {
        return dom;
    }

    public Element definitions() {
        return definitions;
    }

    /** XML namespace of the DMN model elements (identifies the DMN version). */
    public String ns() {
        return definitions.getNamespaceURI();
    }

    /** The model's own namespace ({@code definitions/@namespace}). */
    public String modelNamespace() {
        return attr(definitions, "namespace");
    }

    public String version() {
        return DmnNamespaces.version(ns());
    }

    public boolean isModelElement(Node node, String localName) {
        return node instanceof Element e && ns().equals(e.getNamespaceURI())
                && (localName == null || localName.equals(e.getLocalName()));
    }

    public List<Element> children(Element parent) {
        return children(parent, null);
    }

    /** Direct child elements in the model namespace, optionally filtered by local name. */
    public List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (isModelElement(n, localName)) {
                result.add((Element) n);
            }
        }
        return result;
    }

    public Optional<Element> child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (isModelElement(n, localName)) {
                return Optional.of((Element) n);
            }
        }
        return Optional.empty();
    }

    public Element childOrCreate(Element parent, String localName) {
        return child(parent, localName).orElseGet(() -> insert(parent, create(localName)));
    }

    /** Direct child elements in any namespace with the given local name. */
    public static List<Element> anyChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                result.add(e);
            }
        }
        return result;
    }

    public Optional<Element> findById(String id) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        NodeList all = dom.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("id"))) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public List<Element> allModelElements(String localName) {
        NodeList list = dom.getElementsByTagNameNS(ns(), localName);
        List<Element> result = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            result.add((Element) list.item(i));
        }
        return result;
    }

    /** Creates an element in the model namespace using the document's prefix convention. */
    public Element create(String localName) {
        String prefix = definitions.getPrefix();
        return dom.createElementNS(ns(), prefix == null ? localName : prefix + ":" + localName);
    }

    /** Creates a DMNDI / DC / DI element, declaring the namespace prefix on the root if needed. */
    public Element createDi(String namespace, String localName) {
        String hint = DmnNamespaces.isDc(namespace) ? "dc" : DmnNamespaces.isDi(namespace) ? "di" : "dmndi";
        return dom.createElementNS(namespace, prefixFor(namespace, hint) + ":" + localName);
    }

    private String prefixFor(String namespace, String hint) {
        String existing = definitions.lookupPrefix(namespace);
        if (existing != null) {
            return existing;
        }
        String prefix = hint;
        for (int i = 2; definitions.lookupNamespaceURI(prefix) != null; i++) {
            prefix = hint + i;
        }
        definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, namespace);
        return prefix;
    }

    /** Inserts a child respecting the DMN schema sequence order of the parent. */
    public Element insert(Element parent, Element child) {
        int rank = SchemaOrder.rank(parent.getLocalName(), child.getLocalName());
        if (rank >= 0) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element sibling
                        && SchemaOrder.rank(parent.getLocalName(), sibling.getLocalName()) > rank) {
                    parent.insertBefore(child, n);
                    return child;
                }
            }
        }
        parent.appendChild(child);
        return child;
    }

    public String uniqueId(String prefix) {
        if (ids == null) {
            ids = new HashSet<>();
            NodeList all = dom.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < all.getLength(); i++) {
                String id = ((Element) all.item(i)).getAttribute("id");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        String id;
        do {
            id = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        } while (!ids.add(id));
        return id;
    }

    /** Returns the element id, assigning a fresh one when missing (requirements often lack ids). */
    public String ensureId(Element element, String prefix) {
        String id = element.getAttribute("id");
        if (id.isEmpty()) {
            id = uniqueId(prefix);
            element.setAttribute("id", id);
        }
        return id;
    }

    // ---- DMNDI ---------------------------------------------------------------------------------

    public boolean supportsDmndi() {
        return DmnNamespaces.supportsDmndi(ns());
    }

    public Optional<Element> dmndi() {
        for (Node n = definitions.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && "DMNDI".equals(e.getLocalName())
                    && DmnNamespaces.isDmnDi(e.getNamespaceURI())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public String dmndiNs() {
        return dmndi().map(Element::getNamespaceURI).orElse(DmnNamespaces.dmndiFor(ns()));
    }

    public Element dmndiOrCreate() {
        return dmndi().orElseGet(() -> insert(definitions, createDi(dmndiNs(), "DMNDI")));
    }

    public List<Element> diagrams() {
        return dmndi().map(d -> anyChildren(d, "DMNDiagram")).orElse(List.of());
    }

    public Optional<Element> diagram(String id) {
        List<Element> diagrams = diagrams();
        if (id != null && !id.isBlank()) {
            for (Element d : diagrams) {
                if (id.equals(d.getAttribute("id"))) {
                    return Optional.of(d);
                }
            }
        }
        return diagrams.stream().findFirst();
    }

    /** Resolves a DMNDI {@code dmnElementRef} QName to a local element id, or null if it is imported. */
    public String localIdOfRef(Element context, String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        int colon = ref.indexOf(':');
        if (colon < 0) {
            return ref;
        }
        String prefix = ref.substring(0, colon);
        String ns = context.lookupNamespaceURI(prefix);
        String own = modelNamespace();
        return ns == null || ns.equals(own) ? ref.substring(colon + 1) : null;
    }

    // ---- text & attribute helpers --------------------------------------------------------------

    /** Attribute value or null when absent. */
    public static String attr(Element e, String name) {
        return e != null && e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    public static void setAttr(Element e, String name, String value) {
        if (value == null || value.isBlank()) {
            e.removeAttribute(name);
        } else {
            e.setAttribute(name, value.strip());
        }
    }

    /** Text of the {@code <text>} child, or null. */
    public String text(Element owner) {
        return owner == null ? null : child(owner, "text").map(Element::getTextContent).orElse(null);
    }

    public void setText(Element owner, String value) {
        Element text = childOrCreate(owner, "text");
        text.setTextContent(value == null ? "" : value);
    }

    /** Sets the {@code <text>} of a named child (e.g. inputValues), removing the child when blank. */
    public void setChildText(Element parent, String childName, String value) {
        Optional<Element> existing = child(parent, childName);
        if (value == null || value.isBlank()) {
            existing.ifPresent(parent::removeChild);
        } else {
            setText(existing.orElseGet(() -> insert(parent, create(childName))), value);
        }
    }

    /** Plain text content of a direct child element (e.g. {@code <description>}). */
    public String childContent(Element parent, String childName) {
        return child(parent, childName).map(Element::getTextContent).orElse(null);
    }

    public void setChildContent(Element parent, String childName, String value) {
        Optional<Element> existing = child(parent, childName);
        if (value == null || value.isBlank()) {
            existing.ifPresent(parent::removeChild);
        } else {
            existing.orElseGet(() -> insert(parent, create(childName))).setTextContent(value);
        }
    }

    public static boolean isExpression(String localName) {
        return SchemaOrder.EXPRESSIONS.contains(localName);
    }

    /** First boxed expression child (decision logic, context entry value, ...), if any. */
    public Optional<Element> expressionChild(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns().equals(e.getNamespaceURI()) && isExpression(e.getLocalName())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public List<Element> expressionChildren(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns().equals(e.getNamespaceURI()) && isExpression(e.getLocalName())) {
                result.add(e);
            }
        }
        return result;
    }

    public static void remove(Node node) {
        if (node != null && node.getParentNode() != null) {
            node.getParentNode().removeChild(node);
        }
    }

    /** Parses an XML fragment in the context of this document's root namespace declarations. */
    public Element importFragment(String xml) {
        StringBuilder wrapper = new StringBuilder("<fragment-root");
        var attrs = definitions.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) {
                wrapper.append(' ').append(a.getNodeName()).append("=\"")
                        .append(a.getNodeValue().replace("&", "&amp;").replace("\"", "&quot;")).append('"');
            }
        }
        if (definitions.getPrefix() == null && !definitions.hasAttribute("xmlns")) {
            wrapper.append(" xmlns=\"").append(ns()).append('"');
        }
        wrapper.append('>').append(xml).append("</fragment-root>");
        Element root = DmnXml.parse(wrapper.toString()).getDocumentElement();
        Element found = null;
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                if (found != null) {
                    throw new DmnFormatException("Expected exactly one root element in the fragment");
                }
                found = e;
            }
        }
        if (found == null) {
            throw new DmnFormatException("The fragment contains no element");
        }
        ids = null; // the fragment may bring its own ids
        return (Element) dom.importNode(found, true);
    }
}
