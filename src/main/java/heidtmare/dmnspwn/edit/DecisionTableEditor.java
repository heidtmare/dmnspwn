package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.edit.EditSupport.at;
import static heidtmare.dmnspwn.edit.EditSupport.insertAt;
import static heidtmare.dmnspwn.edit.EditSupport.nz;
import static heidtmare.dmnspwn.xml.DmnDocument.attr;
import static heidtmare.dmnspwn.xml.DmnDocument.setAttr;

import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import heidtmare.dmnspwn.edit.Forms.Action;
import heidtmare.dmnspwn.edit.Forms.DecisionTableForm;
import heidtmare.dmnspwn.edit.Forms.InputColumn;
import heidtmare.dmnspwn.edit.Forms.OutputColumn;
import heidtmare.dmnspwn.edit.Forms.RuleRow;
import heidtmare.dmnspwn.edit.Forms.TableCommand;
import heidtmare.dmnspwn.model.HitPolicy;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Saves the decision table form: cell contents, then one structural change (rows and columns). */
public final class DecisionTableEditor {

    public static final List<String> AGGREGATIONS = List.of("SUM", "COUNT", "MIN", "MAX");

    private final DmnDocument doc;
    private final LogicEditor logic;
    private final ExpressionFactory expressions;

    DecisionTableEditor(DmnDocument doc, LogicEditor logic, ExpressionFactory expressions) {
        this.doc = doc;
        this.logic = logic;
        this.expressions = expressions;
    }

    public void save(String elementId, DecisionTableForm f) {
        Element table = logic.expression(elementId, "decisionTable", "a decision table");
        HitPolicy hitPolicy = HitPolicy.fromAttribute(f.getHitPolicy())
                .orElseThrow(() -> new DmnEditException("Unknown hit policy " + f.getHitPolicy()));
        Action<TableCommand> action = Action.parse(f.getAction(), TableCommand.class);
        table.setAttribute("hitPolicy", hitPolicy.attribute());
        String aggregation = hitPolicy == HitPolicy.COLLECT && AGGREGATIONS.contains(f.getAggregation())
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
                expr.setAttribute("id", doc.uniqueId(DmnDocument.idPrefix("inputExpression")));
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
        apply(table, action);
    }

    private void setTexts(Element rule, String childName, List<String> values) {
        List<Element> entries = doc.children(rule, childName);
        for (int k = 0; k < Math.min(entries.size(), values.size()); k++) {
            doc.setText(entries.get(k), nz(values.get(k)).strip());
        }
    }

    private void apply(Element table, Action<TableCommand> action) {
        List<Element> inputs = doc.children(table, "input");
        List<Element> outputs = doc.children(table, "output");
        List<Element> annotations = doc.children(table, "annotation");
        List<Element> rules = doc.children(table, "rule");
        int i = action.row();
        switch (action.command()) {
            case SAVE -> {
            }
            case ADD_RULE -> insertAt(doc, table, rules, i < 0 ? rules.size() : i + 1,
                    expressions.rule(inputs.size(), outputs.size(), annotations.size()));
            case DUPLICATE_RULE -> {
                Element source = at(rules, i);
                Element copy = (Element) source.cloneNode(true);
                reassignIds(copy);
                table.insertBefore(copy, source.getNextSibling());
            }
            case DELETE_RULE -> DmnDocument.remove(at(rules, i));
            case MOVE_RULE_UP -> {
                if (i > 0) {
                    table.insertBefore(at(rules, i), rules.get(i - 1));
                }
            }
            case MOVE_RULE_DOWN -> {
                if (i + 1 < rules.size()) {
                    table.insertBefore(rules.get(i + 1), at(rules, i));
                }
            }
            case ADD_INPUT -> {
                int pos = i >= 0 ? Math.min(i + 1, inputs.size()) : inputs.size();
                insertAt(doc, table, inputs, pos, expressions.inputClause("", "string"));
                for (Element rule : rules) {
                    insertAt(doc, rule, doc.children(rule, "inputEntry"), pos, expressions.inputEntry());
                }
            }
            case DELETE_INPUT -> removeColumn(inputs, rules, "inputEntry", i);
            case ADD_OUTPUT -> {
                if (outputs.size() == 1 && attr(outputs.getFirst(), "name") == null) {
                    outputs.getFirst().setAttribute("name", "output 1");
                }
                doc.insert(table, expressions.outputClause("output " + (outputs.size() + 1), "string"));
                rules.forEach(rule -> doc.insert(rule, expressions.outputEntry()));
            }
            case DELETE_OUTPUT -> {
                if (outputs.size() <= 1) {
                    throw new DmnEditException("A decision table needs at least one output");
                }
                removeColumn(outputs, rules, "outputEntry", i);
            }
            case ADD_ANNOTATION -> {
                doc.insert(table, expressions.annotationClause(
                        annotations.isEmpty() ? "Annotation" : "Annotation " + (annotations.size() + 1)));
                rules.forEach(rule -> doc.insert(rule, expressions.annotationEntry()));
            }
            case DELETE_ANNOTATION -> removeColumn(annotations, rules, "annotationEntry", i);
        }
    }

    /** Removes the column's clause and its entry from every rule. */
    private void removeColumn(List<Element> clauses, List<Element> rules, String entryName, int index) {
        DmnDocument.remove(at(clauses, index));
        for (Element rule : rules) {
            List<Element> entries = doc.children(rule, entryName);
            if (index < entries.size()) {
                DmnDocument.remove(entries.get(index));
            }
        }
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
}
