package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.xml.DmnDocument.attr;
import static heidtmare.dmnspwn.xml.DmnDocument.setAttr;

import java.util.List;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.model.ConnectionElement;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.HitPolicy;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Creates new boxed expressions and their parts, with fresh ids. */
final class ExpressionFactory {

    private final DmnDocument doc;

    ExpressionFactory(DmnDocument doc) {
        this.doc = doc;
    }

    /** A new expression of the given type for {@code owner}, pre-filled from its requirements where useful. */
    Element create(LogicType type, Element owner) {
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
        table.setAttribute("hitPolicy", HitPolicy.UNIQUE.attribute());
        int inputs = 0;
        for (Element source : requiredElements(owner, ConnectionKind.INFORMATION)) {
            table.appendChild(inputClause(source.getAttribute("name"), variableType(source)));
            inputs++;
        }
        if (inputs == 0) {
            table.appendChild(inputClause("", null));
            inputs = 1;
        }
        table.appendChild(outputClause(attr(owner, "name"), variableType(owner)));
        table.appendChild(rule(inputs, 1, 0));
    }

    /** Pre-fills an invocation with the first required BKM and its parameters. */
    private void initInvocation(Element invocation, Element owner) {
        Element bkm = requiredElements(owner, ConnectionKind.KNOWLEDGE).stream().findFirst().orElse(null);
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

    /** The local elements {@code owner} requires through requirements of the given kind, in document order. */
    private List<Element> requiredElements(Element owner, ConnectionKind kind) {
        DmnReader reader = new DmnReader(doc);
        return ConnectionElement.all(doc).stream()
                .filter(c -> c.kind() == kind && c.element().getParentNode() == owner && c.sourceId() != null)
                .map(c -> reader.nodeElements().get(c.sourceId()))
                .filter(e -> e != null)
                .toList();
    }

    private String variableType(Element element) {
        return doc.child(element, "variable").map(v -> attr(v, "typeRef")).orElse(null);
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

    Element literal(String text) {
        Element literal = doc.create("literalExpression");
        literal.setAttribute("id", doc.uniqueId("LiteralExpression"));
        doc.setText(literal, text);
        return literal;
    }

    // ---- decision table parts ------------------------------------------------------------------

    Element inputClause(String expression, String typeRef) {
        Element input = doc.create("input");
        input.setAttribute("id", doc.uniqueId("InputClause"));
        Element expr = doc.create("inputExpression");
        expr.setAttribute("id", doc.uniqueId("LiteralExpression"));
        setAttr(expr, "typeRef", typeRef);
        doc.setText(expr, expression);
        input.appendChild(expr);
        return input;
    }

    Element outputClause(String name, String typeRef) {
        Element output = doc.create("output");
        output.setAttribute("id", doc.uniqueId("OutputClause"));
        setAttr(output, "name", name);
        setAttr(output, "typeRef", typeRef);
        return output;
    }

    Element annotationClause(String name) {
        Element annotation = doc.create("annotation");
        annotation.setAttribute("name", name);
        return annotation;
    }

    /** A rule with an empty entry per column: {@code -} for inputs, blank for outputs and annotations. */
    Element rule(int inputs, int outputs, int annotations) {
        Element rule = doc.create("rule");
        rule.setAttribute("id", doc.uniqueId("DecisionRule"));
        for (int i = 0; i < inputs; i++) {
            rule.appendChild(inputEntry());
        }
        for (int i = 0; i < outputs; i++) {
            rule.appendChild(outputEntry());
        }
        for (int i = 0; i < annotations; i++) {
            rule.appendChild(annotationEntry());
        }
        return rule;
    }

    Element inputEntry() {
        Element entry = doc.create("inputEntry");
        entry.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix("inputEntry")));
        doc.setText(entry, "-");
        return entry;
    }

    Element outputEntry() {
        Element entry = doc.create("outputEntry");
        entry.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix("outputEntry")));
        doc.setText(entry, "");
        return entry;
    }

    Element annotationEntry() {
        Element entry = doc.create("annotationEntry");
        doc.setText(entry, "");
        return entry;
    }
}
