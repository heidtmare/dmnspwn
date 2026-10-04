package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.edit.EditSupport.required;
import static heidtmare.dmnspwn.xml.DmnDocument.setAttr;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.edit.Forms.ElementForm;
import heidtmare.dmnspwn.edit.Forms.ServiceForm;
import heidtmare.dmnspwn.model.ConnectionElement;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.Href;

/**
 * Mutations of a DMN model. Each method edits the DOM in place, preserving unknown content. Elements and decision
 * services are edited here; connections, logic, decision tables, data types, diagrams and version conversion have
 * their own editors.
 */
public final class DmnEditor {

    private final DmnDocument doc;
    private final DiagramEditor diagrams;
    private final ConnectionEditor connections;
    private final LogicEditor logic;
    private final DecisionTableEditor tables;
    private final ItemDefinitionEditor types;
    private final ModelConverter converter;

    public DmnEditor(DmnDocument doc) {
        this.doc = doc;
        this.diagrams = new DiagramEditor(doc);
        this.connections = new ConnectionEditor(doc, diagrams);
        ExpressionFactory expressions = new ExpressionFactory(doc);
        this.logic = new LogicEditor(doc, expressions);
        this.tables = new DecisionTableEditor(doc, logic, expressions);
        this.types = new ItemDefinitionEditor(doc);
        this.converter = new ModelConverter(doc);
    }

    public DmnDocument document() {
        return doc;
    }

    public DiagramEditor diagrams() {
        return diagrams;
    }

    public ConnectionEditor connections() {
        return connections;
    }

    public LogicEditor logic() {
        return logic;
    }

    public DecisionTableEditor tables() {
        return tables;
    }

    public ItemDefinitionEditor types() {
        return types;
    }

    public ModelConverter converter() {
        return converter;
    }

    // ---- definitions ---------------------------------------------------------------------------

    public void updateDefinitions(String name, String namespace, String description) {
        Element d = doc.definitions();
        d.setAttribute("name", required(name, "Model name"));
        d.setAttribute("namespace", required(namespace, "Namespace"));
        doc.setChildContent(d, "description", description);
    }

    // ---- elements ------------------------------------------------------------------------------

    public String addElement(ElementKind kind, String name, String diagramId) {
        String label = required(name, kind == ElementKind.TEXT_ANNOTATION ? "Text" : "Name");
        Element e = doc.create(kind.localName());
        String id = doc.uniqueId(DmnDocument.idPrefix(kind.localName()));
        e.setAttribute("id", id);
        if (kind == ElementKind.TEXT_ANNOTATION) {
            doc.setText(e, label);
        } else {
            e.setAttribute("name", label);
        }
        if (kind.hasVariable()) {
            Element variable = doc.create("variable");
            variable.setAttribute("id", doc.uniqueId("InformationItem"));
            variable.setAttribute("name", label);
            doc.insert(e, variable);
        }
        doc.insert(doc.definitions(), e);
        if (doc.supportsDmndi()) {
            doc.diagram(diagramId).ifPresent(d -> diagrams.addShape(d, id, kind, null));
        }
        return id;
    }

    public void updateElement(String id, ElementForm f) {
        Element e = node(id);
        ElementKind kind = DmnReader.kindOf(e);
        if (kind == ElementKind.TEXT_ANNOTATION) {
            doc.setText(e, required(f.getText(), "Text"));
            return;
        }
        String name = required(f.getName(), "Name");
        e.setAttribute("name", name);
        setAttr(e, "label", f.getLabel());
        doc.setChildContent(e, "description", f.getDescription());
        if (kind.hasVariable()) {
            Element variable = doc.childOrCreate(e, "variable");
            if (!variable.hasAttribute("id")) {
                variable.setAttribute("id", doc.uniqueId("InformationItem"));
            }
            variable.setAttribute("name", name);
            setAttr(variable, "typeRef", f.getTypeRef());
        }
        if (kind == ElementKind.DECISION) {
            doc.setChildContent(e, "question", f.getQuestion());
            doc.setChildContent(e, "allowedAnswers", f.getAllowedAnswers());
        }
        if (kind == ElementKind.KNOWLEDGE_SOURCE) {
            doc.setChildContent(e, "type", f.getKnowledgeType());
            setAttr(e, "locationURI", f.getLocationUri());
        }
    }

    /** Deletes an element together with every connection, reference and shape pointing at it. */
    public void deleteElement(String id) {
        Element e = node(id);
        Set<String> removedConnections = new HashSet<>();
        for (ConnectionElement c : ConnectionElement.all(doc)) {
            if (c.touches(id)) {
                if (c.id() != null) {
                    removedConnections.add(c.id());
                }
                // The element's own requirements go with it.
                if (!id.equals(c.targetId()) || !c.kind().isRequirement()) {
                    DmnDocument.remove(c.element());
                }
            }
        }
        for (Element ds : doc.children(doc.definitions(), "decisionService")) {
            for (Element ref : doc.children(ds)) {
                if (ref.hasAttribute("href") && references(ref, id)) {
                    DmnDocument.remove(ref);
                }
            }
        }
        diagrams.purge(id, removedConnections);
        DmnDocument.remove(e);
    }

    // ---- decision services ---------------------------------------------------------------------

    public void updateService(String id, ServiceForm f) {
        Element ds = node(id);
        if (DmnReader.kindOf(ds) != ElementKind.DECISION_SERVICE) {
            throw new DmnEditException("Not a decision service");
        }
        check(f.getOutputDecisions(), "outputDecision", ElementKind.DECISION);
        check(f.getEncapsulatedDecisions(), "encapsulatedDecision", ElementKind.DECISION);
        check(f.getInputDecisions(), "inputDecision", ElementKind.DECISION);
        check(f.getInputData(), "inputData", ElementKind.INPUT_DATA);
        for (String child : List.of("outputDecision", "encapsulatedDecision", "inputDecision", "inputData")) {
            doc.children(ds, child).forEach(DmnDocument::remove);
        }
        addRefs(ds, "outputDecision", f.getOutputDecisions(), ElementKind.DECISION);
        addRefs(ds, "encapsulatedDecision", f.getEncapsulatedDecisions(), ElementKind.DECISION);
        addRefs(ds, "inputDecision", f.getInputDecisions(), ElementKind.DECISION);
        addRefs(ds, "inputData", f.getInputData(), ElementKind.INPUT_DATA);
    }

    private void check(List<String> ids, String childName, ElementKind expected) {
        for (String refId : ids == null ? List.<String>of() : ids) {
            if (refId != null && !refId.isBlank() && DmnReader.kindOf(node(refId)) != expected) {
                throw new DmnEditException(childName + " must reference a " + expected.displayName());
            }
        }
    }

    private void addRefs(Element ds, String childName, List<String> ids, ElementKind expected) {
        for (String refId : ids == null ? List.<String>of() : ids) {
            if (refId == null || refId.isBlank()) {
                continue;
            }
            Element ref = doc.create(childName);
            ref.setAttribute("href", Href.local(refId));
            doc.insert(ds, ref);
        }
    }


    // ---- helpers -------------------------------------------------------------------------------

    /** A DRG element or text annotation by id. */
    public Element node(String id) {
        return EditSupport.node(doc, id);
    }

    private boolean references(Element ref, String id) {
        Href href = Href.parse(ref.getAttribute("href"));
        return href.isLocal(doc.modelNamespace()) && id.equals(href.id());
    }
}
