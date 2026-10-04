package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.edit.EditSupport.at;
import static heidtmare.dmnspwn.edit.EditSupport.node;
import static heidtmare.dmnspwn.edit.EditSupport.required;
import static heidtmare.dmnspwn.xml.DmnDocument.attr;
import static heidtmare.dmnspwn.xml.DmnDocument.setAttr;

import java.util.List;
import java.util.Optional;

import javax.xml.XMLConstants;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import heidtmare.dmnspwn.edit.Forms.Action;
import heidtmare.dmnspwn.edit.Forms.ParameterCommand;
import heidtmare.dmnspwn.edit.Forms.ParameterRow;
import heidtmare.dmnspwn.edit.Forms.ParametersForm;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnFormatException;

/** Edits the logic of decisions and business knowledge models, and the parameters of the latter. */
public final class LogicEditor {

    private final DmnDocument doc;
    private final ExpressionFactory expressions;

    LogicEditor(DmnDocument doc, ExpressionFactory expressions) {
        this.doc = doc;
        this.expressions = expressions;
    }

    /** The boxed expression holding the element's logic, if it has one. */
    public Optional<Element> expression(String elementId) {
        return DmnReader.logicElement(doc, node(doc, elementId));
    }

    /** The element's logic if it is the given kind of expression. */
    Element expression(String elementId, String localName, String what) {
        return expression(elementId).filter(e -> localName.equals(e.getLocalName()))
                .orElseThrow(() -> new DmnEditException("The element's logic is not " + what));
    }

    public void setType(String elementId, LogicType type) {
        Element container = container(elementId);
        doc.expressionChild(container).ifPresent(DmnDocument::remove);
        if (type == LogicType.NONE) {
            return;
        }
        if (!LogicType.available(doc.ns()).contains(type)) {
            throw new DmnEditException(type.displayName() + " requires DMN 1.4 or later");
        }
        Element owner = node(doc, elementId);
        Element expr = expressions.create(type, owner);
        if (DmnReader.kindOf(owner) == ElementKind.DECISION) {
            setAttr(expr, "typeRef", doc.child(owner, "variable").map(v -> attr(v, "typeRef")).orElse(null));
        }
        doc.insert(container, expr);
    }

    public void saveLiteral(String elementId, String text, String typeRef) {
        Element literal = expression(elementId, "literalExpression", "a literal expression");
        doc.setText(literal, text);
        setAttr(literal, "typeRef", typeRef);
    }

    /** Replaces the logic with an expression written as XML (any boxed expression type). */
    public void replaceXml(String elementId, String xml) {
        Element container = container(elementId);
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

    // ---- BKM parameters ------------------------------------------------------------------------

    public void saveParameters(String elementId, ParametersForm f) {
        Element bkm = node(doc, elementId);
        if (DmnReader.kindOf(bkm) != ElementKind.BUSINESS_KNOWLEDGE_MODEL) {
            throw new DmnEditException("Only business knowledge models have parameters");
        }
        Action<ParameterCommand> action = Action.parse(f.getAction(), ParameterCommand.class);
        Element fn = encapsulatedLogic(bkm);
        String kind = f.getFunctionKind();
        setAttr(fn, "kind", kind == null || kind.isBlank() ? "FEEL" : kind);
        List<Element> params = doc.children(fn, "formalParameter");
        for (int i = 0; i < Math.min(params.size(), f.getParameters().size()); i++) {
            ParameterRow row = f.getParameters().get(i);
            params.get(i).setAttribute("name", required(row.getName(), "Parameter name"));
            setAttr(params.get(i), "typeRef", row.getTypeRef());
        }
        int i = action.row();
        switch (action.command()) {
            case SAVE -> {
            }
            case ADD_PARAMETER -> {
                Element p = doc.create("formalParameter");
                p.setAttribute("id", doc.uniqueId("InformationItem"));
                p.setAttribute("name", "param" + (params.size() + 1));
                doc.insert(fn, p);
            }
            case DELETE_PARAMETER -> DmnDocument.remove(at(params, i));
            case MOVE_PARAMETER_UP -> {
                if (i > 0) {
                    fn.insertBefore(at(params, i), params.get(i - 1));
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The element the logic expression is a child of: the decision, or the BKM's function definition. */
    private Element container(String elementId) {
        Element e = node(doc, elementId);
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
}
