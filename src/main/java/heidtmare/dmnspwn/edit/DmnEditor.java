package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.xml.DmnDocument.attr;
import static heidtmare.dmnspwn.xml.DmnDocument.setAttr;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.edit.ConnectionElements.Conn;
import heidtmare.dmnspwn.edit.Forms.Action;
import heidtmare.dmnspwn.edit.Forms.ComponentRow;
import heidtmare.dmnspwn.edit.Forms.DecisionTableForm;
import heidtmare.dmnspwn.edit.Forms.ElementForm;
import heidtmare.dmnspwn.edit.Forms.InputColumn;
import heidtmare.dmnspwn.edit.Forms.ItemDefinitionForm;
import heidtmare.dmnspwn.edit.Forms.OutputColumn;
import heidtmare.dmnspwn.edit.Forms.ParameterRow;
import heidtmare.dmnspwn.edit.Forms.ParametersForm;
import heidtmare.dmnspwn.edit.Forms.RuleRow;
import heidtmare.dmnspwn.edit.Forms.ServiceForm;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnFormatException;
import heidtmare.dmnspwn.xml.DmnNamespaces;
import heidtmare.dmnspwn.xml.Href;

/** Mutations of a DMN model. Each method edits the DOM in place, preserving unknown content. */
public final class DmnEditor {

    public static final List<String> HIT_POLICIES =
            List.of("UNIQUE", "FIRST", "PRIORITY", "ANY", "COLLECT", "RULE ORDER", "OUTPUT ORDER");
    public static final List<String> AGGREGATIONS = List.of("SUM", "COUNT", "MIN", "MAX");

    private final DmnDocument doc;
    private final DiagramEditor diagrams;

    public DmnEditor(DmnDocument doc) {
        this.doc = doc;
        this.diagrams = new DiagramEditor(doc);
    }

    public DmnDocument document() {
        return doc;
    }

    public DiagramEditor diagrams() {
        return diagrams;
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
        for (Conn c : ConnectionElements.all(doc)) {
            if (id.equals(c.sourceId()) || id.equals(c.targetId())) {
                String connId = c.element().getAttribute("id");
                if (!connId.isEmpty()) {
                    removedConnections.add(connId);
                }
                if (!id.equals(c.targetId()) || "association".equals(c.element().getLocalName())) {
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

    // ---- connections ---------------------------------------------------------------------------

    public ConnectionKind connect(String sourceId, String targetId) {
        if (sourceId == null || sourceId.equals(targetId)) {
            throw new DmnEditException("Choose two different elements to connect");
        }
        Element source = node(sourceId);
        Element target = node(targetId);
        ElementKind sk = DmnReader.kindOf(source);
        ElementKind tk = DmnReader.kindOf(target);
        ConnectionKind kind = ConnectionKind.between(sk, tk).orElseThrow(() -> new DmnEditException(
                "DMN does not allow a connection from a %s to a %s".formatted(sk.displayName(), tk.displayName())));

        for (Conn c : ConnectionElements.all(doc)) {
            boolean same = sourceId.equals(c.sourceId()) && targetId.equals(c.targetId())
                    || kind == ConnectionKind.ASSOCIATION && sourceId.equals(c.targetId())
                    && targetId.equals(c.sourceId());
            if (same && kind.localName().equals(c.element().getLocalName())) {
                throw new DmnEditException("These elements are already connected");
            }
        }

        if (kind == ConnectionKind.ASSOCIATION) {
            Element assoc = doc.create("association");
            assoc.setAttribute("id", doc.uniqueId("Association"));
            Element src = doc.create("sourceRef");
            src.setAttribute("href", Href.local(sourceId));
            Element tgt = doc.create("targetRef");
            tgt.setAttribute("href", Href.local(targetId));
            assoc.appendChild(src);
            assoc.appendChild(tgt);
            doc.insert(doc.definitions(), assoc);
        } else {
            if (dependsOn(sourceId, targetId)) {
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

    /** Removes a connection by id or by the synthetic {@code target|kind|source} reference. */
    public void disconnect(String ref) {
        Element conn = null;
        if (ref != null && ref.contains("|")) {
            String[] parts = ref.split("\\|", 3);
            for (Conn c : ConnectionElements.all(doc)) {
                boolean match = "association".equals(parts[0])
                        ? "association".equals(c.element().getLocalName()) && parts[1].equals(c.sourceId())
                        && parts[2].equals(c.targetId())
                        : parts[0].equals(c.targetId()) && parts[1].equals(c.element().getLocalName())
                        && parts[2].equals(c.sourceId());
                if (match) {
                    conn = c.element();
                    break;
                }
            }
        } else {
            conn = doc.findById(ref).filter(e -> ConnectionElements.REQUIREMENTS.contains(e.getLocalName())
                    || "association".equals(e.getLocalName())).orElse(null);
        }
        if (conn == null) {
            throw new DmnEditException("Connection not found");
        }
        String id = conn.getAttribute("id");
        DmnDocument.remove(conn);
        if (!id.isEmpty()) {
            diagrams.purge(null, Set.of(id));
        }
    }

    /** Whether {@code a} (transitively) requires {@code b}. */
    private boolean dependsOn(String a, String b) {
        List<Conn> all = ConnectionElements.all(doc);
        Deque<String> queue = new ArrayDeque<>(List.of(a));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.pop();
            if (current.equals(b)) {
                return true;
            }
            if (seen.add(current)) {
                for (Conn c : all) {
                    if (current.equals(c.targetId()) && c.sourceId() != null
                            && !"association".equals(c.element().getLocalName())) {
                        queue.push(c.sourceId());
                    }
                }
            }
        }
        return false;
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

    // ---- decision logic ------------------------------------------------------------------------

    public Optional<Element> logic(String elementId) {
        Element e = node(elementId);
        return switch (DmnReader.kindOf(e)) {
            case DECISION -> doc.expressionChild(e);
            case BUSINESS_KNOWLEDGE_MODEL -> doc.child(e, "encapsulatedLogic").flatMap(doc::expressionChild);
            default -> Optional.empty();
        };
    }

    private Element logicContainer(String elementId) {
        Element e = node(elementId);
        return switch (DmnReader.kindOf(e)) {
            case DECISION -> e;
            case BUSINESS_KNOWLEDGE_MODEL -> encapsulatedLogic(e);
            default -> throw new DmnEditException("Only decisions and business knowledge models have logic");
        };
    }

    private Element encapsulatedLogic(Element bkm) {
        Element fn = doc.childOrCreate(bkm, "encapsulatedLogic");
        if (!fn.hasAttribute("id")) {
            fn.setAttribute("id", doc.uniqueId("FunctionDefinition"));
        }
        return fn;
    }

    public void setLogicType(String elementId, LogicType type) {
        Element container = logicContainer(elementId);
        doc.expressionChild(container).ifPresent(DmnDocument::remove);
        if (type == LogicType.NONE) {
            return;
        }
        if (!LogicType.available(doc.ns()).contains(type)) {
            throw new DmnEditException(type.displayName() + " requires DMN 1.4 or later");
        }
        Element owner = node(elementId);
        Element expr = newExpression(type, owner);
        String typeRef = doc.child(owner, "variable").map(v -> attr(v, "typeRef")).orElse(null);
        if (DmnReader.kindOf(owner) == ElementKind.DECISION) {
            setAttr(expr, "typeRef", typeRef);
        }
        doc.insert(container, expr);
    }

    private Element newExpression(LogicType type, Element owner) {
        Element expr = doc.create(type.localName());
        expr.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix(type.localName())));
        switch (type) {
            case LITERAL -> doc.setText(expr, "");
            case DECISION_TABLE -> initDecisionTable(expr, owner);
            case CONTEXT -> {
                expr.appendChild(contextEntry("entry 1"));
                expr.appendChild(contextEntry(null));
            }
            case RELATION -> {
                Element column = doc.create("column");
                column.setAttribute("id", doc.uniqueId("InformationItem"));
                column.setAttribute("name", "column 1");
                expr.appendChild(column);
                Element row = doc.create("row");
                row.setAttribute("id", doc.uniqueId("List"));
                row.appendChild(literal(""));
                expr.appendChild(row);
            }
            case LIST -> expr.appendChild(literal(""));
            case INVOCATION -> initInvocation(expr, owner);
            case FUNCTION -> {
                expr.setAttribute("kind", "FEEL");
                expr.appendChild(literal(""));
            }
            case CONDITIONAL -> {
                expr.appendChild(wrap("if", literal("")));
                expr.appendChild(wrap("then", literal("")));
                expr.appendChild(wrap("else", literal("")));
            }
            case FOR, SOME, EVERY -> {
                expr.setAttribute("iteratorVariable", "item");
                expr.appendChild(wrap("in", literal("")));
                expr.appendChild(wrap(type == LogicType.FOR ? "return" : "satisfies", literal("")));
            }
            case FILTER -> {
                expr.appendChild(wrap("in", literal("")));
                expr.appendChild(wrap("match", literal("")));
            }
            case NONE -> throw new IllegalArgumentException();
        }
        return expr;
    }

    /** Seeds a decision table with one input per information requirement of the decision. */
    private void initDecisionTable(Element table, Element owner) {
        table.setAttribute("hitPolicy", "UNIQUE");
        DmnReader reader = new DmnReader(doc);
        int inputs = 0;
        for (Element req : doc.children(owner, "informationRequirement")) {
            Optional<Element> ref = doc.children(req).stream().filter(c -> c.hasAttribute("href")).findFirst();
            Optional<Element> required = ref.map(r -> Href.parse(r.getAttribute("href")))
                    .filter(h -> h.isLocal(doc.modelNamespace()))
                    .map(h -> reader.nodeElements().get(h.id()));
            if (required.isPresent()) {
                Element source = required.get();
                String typeRef = doc.child(source, "variable").map(v -> attr(v, "typeRef")).orElse(null);
                table.appendChild(inputClause(source.getAttribute("name"), typeRef));
                inputs++;
            }
        }
        if (inputs == 0) {
            table.appendChild(inputClause("", null));
            inputs = 1;
        }
        Element output = doc.create("output");
        output.setAttribute("id", doc.uniqueId("OutputClause"));
        setAttr(output, "name", owner.getAttribute("name"));
        setAttr(output, "typeRef", doc.child(owner, "variable").map(v -> attr(v, "typeRef")).orElse(null));
        table.appendChild(output);
        table.appendChild(newRule(inputs, 1, 0));
    }

    /** Pre-fills an invocation with the first required BKM and its parameters. */
    private void initInvocation(Element invocation, Element owner) {
        DmnReader reader = new DmnReader(doc);
        Element bkm = doc.children(owner, "knowledgeRequirement").stream()
                .flatMap(r -> doc.children(r, "requiredKnowledge").stream())
                .map(r -> Href.parse(r.getAttribute("href")))
                .filter(h -> h.isLocal(doc.modelNamespace()))
                .map(h -> reader.nodeElements().get(h.id()))
                .filter(e -> e != null).findFirst().orElse(null);
        invocation.appendChild(literal(bkm == null ? "" : bkm.getAttribute("name")));
        if (bkm == null) {
            return;
        }
        for (Element p : doc.child(bkm, "encapsulatedLogic").map(fn -> doc.children(fn, "formalParameter"))
                .orElse(List.of())) {
            Element binding = doc.create("binding");
            Element param = doc.create("parameter");
            param.setAttribute("id", doc.uniqueId("InformationItem"));
            param.setAttribute("name", p.getAttribute("name"));
            setAttr(param, "typeRef", attr(p, "typeRef"));
            binding.appendChild(param);
            binding.appendChild(literal(""));
            invocation.appendChild(binding);
        }
    }

    private Element contextEntry(String name) {
        Element entry = doc.create("contextEntry");
        if (name != null) {
            Element variable = doc.create("variable");
            variable.setAttribute("id", doc.uniqueId("InformationItem"));
            variable.setAttribute("name", name);
            entry.appendChild(variable);
        }
        entry.appendChild(literal(""));
        return entry;
    }

    private Element wrap(String childName, Element expression) {
        Element child = doc.create(childName);
        child.appendChild(expression);
        return child;
    }

    private Element literal(String text) {
        Element literal = doc.create("literalExpression");
        literal.setAttribute("id", doc.uniqueId("LiteralExpression"));
        doc.setText(literal, text);
        return literal;
    }

    public void updateLiteral(String elementId, String text, String typeRef) {
        Element literal = logic(elementId).filter(e -> "literalExpression".equals(e.getLocalName()))
                .orElseThrow(() -> new DmnEditException("The element's logic is not a literal expression"));
        doc.setText(literal, text);
        setAttr(literal, "typeRef", typeRef);
    }

    /** Replaces the logic with an expression written as XML (any boxed expression type). */
    public void replaceLogicXml(String elementId, String xml) {
        Element container = logicContainer(elementId);
        Element imported;
        try {
            imported = doc.importFragment(xml);
        } catch (DmnFormatException e) {
            throw new DmnEditException(e.getMessage());
        }
        if (!doc.ns().equals(imported.getNamespaceURI()) || !DmnDocument.isExpression(imported.getLocalName())) {
            throw new DmnEditException("The root element must be a DMN boxed expression (e.g. <decisionTable>) "
                    + "in the model namespace " + doc.ns());
        }
        removeRedundantNamespaceDeclarations(imported);
        doc.expressionChild(container).ifPresent(DmnDocument::remove);
        doc.insert(container, imported);
    }

    private void removeRedundantNamespaceDeclarations(Element e) {
        NamedNodeMap attrs = e.getAttributes();
        for (int i = attrs.getLength() - 1; i >= 0; i--) {
            Attr a = (Attr) attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) {
                String prefix = "xmlns".equals(a.getName()) ? null : a.getLocalName();
                String inScope = doc.definitions().lookupNamespaceURI(prefix);
                if (a.getValue().equals(inScope)) {
                    e.removeAttributeNode(a);
                }
            }
        }
    }

    // ---- decision tables -----------------------------------------------------------------------

    public void saveDecisionTable(String elementId, DecisionTableForm f) {
        Element table = logic(elementId).filter(e -> "decisionTable".equals(e.getLocalName()))
                .orElseThrow(() -> new DmnEditException("The element's logic is not a decision table"));
        String hitPolicy = f.getHitPolicy() == null || f.getHitPolicy().isBlank() ? "UNIQUE" : f.getHitPolicy();
        if (!HIT_POLICIES.contains(hitPolicy)) {
            throw new DmnEditException("Unknown hit policy " + hitPolicy);
        }
        table.setAttribute("hitPolicy", hitPolicy);
        String aggregation = "COLLECT".equals(hitPolicy) && AGGREGATIONS.contains(f.getAggregation())
                ? f.getAggregation() : null;
        setAttr(table, "aggregation", aggregation);
        setAttr(table, "outputLabel", f.getOutputLabel());

        List<Element> inputs = doc.children(table, "input");
        for (int j = 0; j < Math.min(inputs.size(), f.getInputs().size()); j++) {
            InputColumn col = f.getInputs().get(j);
            Element in = inputs.get(j);
            setAttr(in, "label", col.getLabel());
            Element expr = doc.childOrCreate(in, "inputExpression");
            if (!expr.hasAttribute("id")) {
                expr.setAttribute("id", doc.uniqueId("LiteralExpression"));
            }
            doc.setText(expr, nz(col.getExpression()));
            setAttr(expr, "typeRef", col.getTypeRef());
            doc.setChildText(in, "inputValues", col.getInputValues());
        }
        List<Element> outputs = doc.children(table, "output");
        for (int k = 0; k < Math.min(outputs.size(), f.getOutputs().size()); k++) {
            OutputColumn col = f.getOutputs().get(k);
            Element out = outputs.get(k);
            setAttr(out, "name", col.getName());
            setAttr(out, "label", col.getLabel());
            setAttr(out, "typeRef", col.getTypeRef());
            doc.setChildText(out, "outputValues", col.getOutputValues());
            doc.setChildText(out, "defaultOutputEntry", col.getDefaultOutput());
        }
        List<Element> annotations = doc.children(table, "annotation");
        for (int a = 0; a < Math.min(annotations.size(), f.getAnnotations().size()); a++) {
            String name = f.getAnnotations().get(a);
            annotations.get(a).setAttribute("name", name == null || name.isBlank() ? "Annotation" : name.strip());
        }
        List<Element> rules = doc.children(table, "rule");
        for (int i = 0; i < Math.min(rules.size(), f.getRules().size()); i++) {
            RuleRow row = f.getRules().get(i);
            Element rule = rules.get(i);
            setTexts(rule, "inputEntry", row.getInputs());
            setTexts(rule, "outputEntry", row.getOutputs());
            setTexts(rule, "annotationEntry", row.getAnnotations());
            doc.setChildContent(rule, "description", row.getDescription());
        }
        applyTableAction(table, Action.parse(f.getAction()));
    }

    private void setTexts(Element rule, String childName, List<String> values) {
        List<Element> entries = doc.children(rule, childName);
        for (int k = 0; k < Math.min(entries.size(), values.size()); k++) {
            doc.setText(entries.get(k), nz(values.get(k)).strip());
        }
    }

    private void applyTableAction(Element table, Action action) {
        List<Element> inputs = doc.children(table, "input");
        List<Element> outputs = doc.children(table, "output");
        List<Element> annotations = doc.children(table, "annotation");
        List<Element> rules = doc.children(table, "rule");
        int i = action.index();
        switch (action.name()) {
            case "save" -> {
            }
            case "addRule" -> {
                Element rule = newRule(inputs.size(), outputs.size(), annotations.size());
                if (i >= 0 && i + 1 < rules.size()) {
                    table.insertBefore(rule, rules.get(i + 1));
                } else {
                    doc.insert(table, rule);
                }
            }
            case "duplicateRule" -> {
                Element source = at(rules, i);
                Element copy = (Element) source.cloneNode(true);
                reassignIds(copy);
                table.insertBefore(copy, source.getNextSibling());
            }
            case "deleteRule" -> DmnDocument.remove(at(rules, i));
            case "moveRuleUp" -> {
                if (i > 0) {
                    table.insertBefore(at(rules, i), rules.get(i - 1));
                }
            }
            case "moveRuleDown" -> {
                if (i >= 0 && i + 1 < rules.size()) {
                    table.insertBefore(rules.get(i + 1), at(rules, i));
                }
            }
            case "addInput" -> {
                int pos = i >= 0 ? Math.min(i + 1, inputs.size()) : inputs.size();
                Element clause = inputClause("", "string");
                if (pos < inputs.size()) {
                    table.insertBefore(clause, inputs.get(pos));
                } else {
                    doc.insert(table, clause);
                }
                for (Element rule : rules) {
                    List<Element> entries = doc.children(rule, "inputEntry");
                    Element entry = unaryTests("-");
                    if (pos < entries.size()) {
                        rule.insertBefore(entry, entries.get(pos));
                    } else {
                        doc.insert(rule, entry);
                    }
                }
            }
            case "deleteInput" -> {
                DmnDocument.remove(at(inputs, i));
                rules.forEach(rule -> {
                    List<Element> entries = doc.children(rule, "inputEntry");
                    if (i < entries.size()) {
                        DmnDocument.remove(entries.get(i));
                    }
                });
            }
            case "addOutput" -> {
                if (outputs.size() == 1 && attr(outputs.getFirst(), "name") == null) {
                    outputs.getFirst().setAttribute("name", "output 1");
                }
                Element output = doc.create("output");
                output.setAttribute("id", doc.uniqueId("OutputClause"));
                output.setAttribute("name", "output " + (outputs.size() + 1));
                output.setAttribute("typeRef", "string");
                doc.insert(table, output);
                rules.forEach(rule -> doc.insert(rule, outputEntry("")));
            }
            case "deleteOutput" -> {
                if (outputs.size() <= 1) {
                    throw new DmnEditException("A decision table needs at least one output");
                }
                DmnDocument.remove(at(outputs, i));
                rules.forEach(rule -> {
                    List<Element> entries = doc.children(rule, "outputEntry");
                    if (i < entries.size()) {
                        DmnDocument.remove(entries.get(i));
                    }
                });
            }
            case "addAnnotation" -> {
                Element annotation = doc.create("annotation");
                annotation.setAttribute("name", annotations.isEmpty() ? "Annotation" : "Annotation " + (annotations.size() + 1));
                doc.insert(table, annotation);
                rules.forEach(rule -> {
                    Element entry = doc.create("annotationEntry");
                    doc.setText(entry, "");
                    doc.insert(rule, entry);
                });
            }
            case "deleteAnnotation" -> {
                DmnDocument.remove(at(annotations, i));
                rules.forEach(rule -> {
                    List<Element> entries = doc.children(rule, "annotationEntry");
                    if (i < entries.size()) {
                        DmnDocument.remove(entries.get(i));
                    }
                });
            }
            default -> throw new DmnEditException("Unknown action " + action.name());
        }
    }

    private Element inputClause(String expression, String typeRef) {
        Element input = doc.create("input");
        input.setAttribute("id", doc.uniqueId("InputClause"));
        Element expr = doc.create("inputExpression");
        expr.setAttribute("id", doc.uniqueId("LiteralExpression"));
        setAttr(expr, "typeRef", typeRef);
        doc.setText(expr, expression);
        input.appendChild(expr);
        return input;
    }

    private Element newRule(int inputs, int outputs, int annotations) {
        Element rule = doc.create("rule");
        rule.setAttribute("id", doc.uniqueId("DecisionRule"));
        for (int i = 0; i < inputs; i++) {
            rule.appendChild(unaryTests("-"));
        }
        for (int i = 0; i < outputs; i++) {
            rule.appendChild(outputEntry(""));
        }
        for (int i = 0; i < annotations; i++) {
            Element entry = doc.create("annotationEntry");
            doc.setText(entry, "");
            rule.appendChild(entry);
        }
        return rule;
    }

    private Element unaryTests(String text) {
        Element entry = doc.create("inputEntry");
        entry.setAttribute("id", doc.uniqueId("UnaryTests"));
        doc.setText(entry, text);
        return entry;
    }

    private Element outputEntry(String text) {
        Element entry = doc.create("outputEntry");
        entry.setAttribute("id", doc.uniqueId("LiteralExpression"));
        doc.setText(entry, text);
        return entry;
    }

    private void reassignIds(Element root) {
        if (root.hasAttribute("id")) {
            root.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix(root.getLocalName())));
        }
        NodeList all = root.getElementsByTagNameNS("*", "*");
        for (int k = 0; k < all.getLength(); k++) {
            Element e = (Element) all.item(k);
            if (e.hasAttribute("id")) {
                e.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix(e.getLocalName())));
            }
        }
    }

    // ---- BKM parameters ------------------------------------------------------------------------

    public void saveParameters(String elementId, ParametersForm f) {
        Element bkm = node(elementId);
        if (DmnReader.kindOf(bkm) != ElementKind.BUSINESS_KNOWLEDGE_MODEL) {
            throw new DmnEditException("Only business knowledge models have parameters");
        }
        Element fn = encapsulatedLogic(bkm);
        String kind = f.getFunctionKind();
        setAttr(fn, "kind", kind == null || kind.isBlank() ? "FEEL" : kind);
        List<Element> params = doc.children(fn, "formalParameter");
        for (int i = 0; i < Math.min(params.size(), f.getParameters().size()); i++) {
            ParameterRow row = f.getParameters().get(i);
            params.get(i).setAttribute("name", required(row.getName(), "Parameter name"));
            setAttr(params.get(i), "typeRef", row.getTypeRef());
        }
        Action action = Action.parse(f.getAction());
        int i = action.index();
        switch (action.name()) {
            case "save" -> {
            }
            case "addParameter" -> {
                Element p = doc.create("formalParameter");
                p.setAttribute("id", doc.uniqueId("InformationItem"));
                p.setAttribute("name", "param" + (params.size() + 1));
                doc.insert(fn, p);
            }
            case "deleteParameter" -> DmnDocument.remove(at(params, i));
            case "moveParameterUp" -> {
                if (i > 0) {
                    fn.insertBefore(at(params, i), params.get(i - 1));
                }
            }
            default -> throw new DmnEditException("Unknown action " + action.name());
        }
    }

    // ---- item definitions ----------------------------------------------------------------------

    public String addItemDefinition(String name, String typeRef, boolean collection) {
        Element item = doc.create("itemDefinition");
        item.setAttribute("id", doc.uniqueId("ItemDefinition"));
        item.setAttribute("name", required(name, "Type name"));
        if (collection) {
            item.setAttribute("isCollection", "true");
        }
        doc.setChildContent(item, "typeRef", typeRef);
        doc.insert(doc.definitions(), item);
        return String.valueOf(doc.children(doc.definitions(), "itemDefinition").size() - 1);
    }

    public void saveItemDefinition(String path, ItemDefinitionForm f) {
        Element item = itemByPath(path);
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
        Action action = Action.parse(f.getAction());
        int i = action.index();
        switch (action.name()) {
            case "save" -> {
            }
            case "addComponent" -> {
                Element c = doc.create("itemComponent");
                c.setAttribute("id", doc.uniqueId("ItemComponent"));
                c.setAttribute("name", "field" + (components.size() + 1));
                doc.setChildContent(c, "typeRef", "string");
                doc.insert(item, c);
                doc.child(item, "typeRef").ifPresent(DmnDocument::remove);
            }
            case "deleteComponent" -> DmnDocument.remove(at(components, i));
            case "moveComponentUp" -> {
                if (i > 0) {
                    item.insertBefore(at(components, i), components.get(i - 1));
                }
            }
            default -> throw new DmnEditException("Unknown action " + action.name());
        }
    }

    public void deleteItemDefinition(String path) {
        DmnDocument.remove(itemByPath(path));
    }

    public Element itemByPath(String path) {
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

    // ---- version conversion --------------------------------------------------------------------

    /** Moves the model to the DMN 1.5 namespaces. Structure is compatible from 1.2; 1.1 is best effort. */
    public void convertToLatest() {
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
        List<Element> snapshot = new java.util.ArrayList<>();
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

    // ---- helpers -------------------------------------------------------------------------------

    /** A DRG element or text annotation by id. */
    public Element node(String id) {
        return doc.findById(id)
                .filter(e -> e.getParentNode() == doc.definitions())
                .filter(e -> ElementKind.fromLocalName(e.getLocalName()).isPresent())
                .orElseThrow(() -> new DmnEditException("No element with id '" + id + "'"));
    }

    private boolean references(Element ref, String id) {
        Href href = Href.parse(ref.getAttribute("href"));
        return href.isLocal(doc.modelNamespace()) && id.equals(href.id());
    }

    private static Element at(List<Element> list, int index) {
        if (index < 0 || index >= list.size()) {
            throw new DmnEditException("Index out of range: " + index);
        }
        return list.get(index);
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new DmnEditException(what + " is required");
        }
        return value.strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Exposed for the element page: the shape bounds of an element on a diagram, if any. */
    public Optional<heidtmare.dmnspwn.diagram.Geometry.Bounds> shapeBounds(String diagramId, String elementId) {
        return doc.diagram(diagramId)
                .map(d -> DiagramBuilder.shapesByElement(doc, d).get(elementId))
                .flatMap(DiagramBuilder::bounds);
    }
}
