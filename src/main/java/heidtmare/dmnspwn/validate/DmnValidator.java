package heidtmare.dmnspwn.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import heidtmare.dmnspwn.diagram.Dmndi;
import heidtmare.dmnspwn.model.BuiltInTypes;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.Views.ConnectionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;
import heidtmare.dmnspwn.validate.Issue.Severity;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Structural checks derived from the DMN specification's well-formedness rules. */
public final class DmnValidator {

    private DmnValidator() {
    }

    public static List<Issue> validate(DmnReader reader) {
        List<Issue> issues = new ArrayList<>();
        DmnDocument doc = reader.document();

        Map<String, Integer> ids = new HashMap<>();
        NodeList all = doc.dom().getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            String id = ((Element) all.item(i)).getAttribute("id");
            if (!id.isEmpty()) {
                ids.merge(id, 1, Integer::sum);
            }
        }
        ids.forEach((id, count) -> {
            if (count > 1) {
                issues.add(new Issue(Severity.ERROR, null, null, "Id '" + id + "' is used " + count + " times"));
            }
        });

        Set<String> types = new HashSet<>();
        collectTypeNames(reader.itemDefinitions(), types);
        Map<String, ElementView> elements = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (ElementView e : reader.elements()) {
            elements.put(e.id(), e);
            if (e.kind() == ElementKind.TEXT_ANNOTATION) {
                continue;
            }
            if (e.name() == null || e.name().isBlank()) {
                issues.add(issue(Severity.ERROR, e, "Missing name"));
            } else if (!names.add(e.name())) {
                issues.add(issue(Severity.WARNING, e, "Another DRG element is also named '" + e.name() + "'"));
            }
            if (e.typeRef() != null && !isKnownType(e.typeRef(), types)) {
                issues.add(issue(Severity.WARNING, e, "Unknown type '" + e.typeRef() + "'"));
            }
            if (e.kind() == ElementKind.DECISION && e.logic() == null) {
                issues.add(issue(Severity.WARNING, e, "Decision has no decision logic"));
            }
            if (e.kind() == ElementKind.BUSINESS_KNOWLEDGE_MODEL && e.logic() == null) {
                issues.add(issue(Severity.WARNING, e, "Business knowledge model has no encapsulated logic"));
            }
            if (e.logic() instanceof ExpressionView.DecisionTable dt) {
                checkTable(e, dt, issues);
            }
            if (e.kind() == ElementKind.DECISION_SERVICE && e.service().outputDecisions().isEmpty()) {
                issues.add(issue(Severity.ERROR, e, "Decision service has no output decisions"));
            }
        }

        for (ConnectionView c : reader.connections()) {
            boolean sourceMissing = c.sourceLocal() && !elements.containsKey(c.sourceId());
            boolean targetMissing = !elements.containsKey(c.targetId());
            if (sourceMissing || targetMissing) {
                issues.add(new Issue(Severity.ERROR, c.targetId(), c.targetName(), c.kind().displayName()
                        + " references missing element '" + (targetMissing ? c.targetId() : c.sourceId()) + "'"));
                continue;
            }
            if (c.sourceKind() != null && c.targetKind() != null
                    && ConnectionKind.between(c.sourceKind(), c.targetKind()).filter(k -> k == c.kind()).isEmpty()) {
                issues.add(new Issue(Severity.ERROR, c.targetId(), c.targetName(),
                        "%s from %s '%s' is not allowed".formatted(c.kind().displayName(),
                                c.sourceKind().displayName(), c.sourceName())));
            }
        }
        checkCycles(reader, elements, issues);
        checkShapes(doc, reader, issues);
        return issues;
    }

    private static void checkTable(ElementView e, ExpressionView.DecisionTable dt, List<Issue> issues) {
        if (dt.outputs().isEmpty()) {
            issues.add(issue(Severity.ERROR, e, "Decision table has no output"));
        }
        if (dt.outputs().size() > 1 && dt.outputs().stream().anyMatch(o -> o.name() == null || o.name().isBlank())) {
            issues.add(issue(Severity.ERROR, e, "Every output of a multi-output decision table needs a name"));
        }
        for (int i = 0; i < dt.rules().size(); i++) {
            ExpressionView.Rule r = dt.rules().get(i);
            if (r.inputs().size() != dt.inputs().size() || r.outputs().size() != dt.outputs().size()) {
                issues.add(issue(Severity.ERROR, e, "Rule " + (i + 1) + " has %d input / %d output entries, expected %d / %d"
                        .formatted(r.inputs().size(), r.outputs().size(), dt.inputs().size(), dt.outputs().size())));
            }
            if (r.inputs().stream().anyMatch(String::isBlank)) {
                issues.add(issue(Severity.WARNING, e, "Rule " + (i + 1) + " has an empty input entry (use '-' for any)"));
            }
            if (r.outputs().stream().anyMatch(String::isBlank)) {
                issues.add(issue(Severity.WARNING, e, "Rule " + (i + 1) + " has an empty output entry"));
            }
        }
        if (dt.rules().isEmpty()) {
            issues.add(issue(Severity.WARNING, e, "Decision table has no rules"));
        }
    }

    private static void checkCycles(DmnReader reader, Map<String, ElementView> elements, List<Issue> issues) {
        for (String id : reader.requirementGraph().cycles(reader.elements().stream().map(ElementView::id).toList())) {
            ElementView e = elements.get(id);
            issues.add(new Issue(Severity.ERROR, id, e == null ? id : e.displayName(),
                    "Requirement cycle involving this element"));
        }
    }

    private static void checkShapes(DmnDocument doc, DmnReader reader, List<Issue> issues) {
        for (Element diagram : doc.diagrams()) {
            for (Element shape : DmnDocument.anyChildren(diagram, "DMNShape")) {
                String ref = shape.getAttribute("dmnElementRef");
                String local = doc.localIdOfRef(shape, ref);
                if (local != null && !reader.nodeElements().containsKey(local)) {
                    issues.add(new Issue(Severity.WARNING, null, null,
                            "Diagram '" + diagram.getAttribute("name") + "' has a shape for unknown element '" + ref + "'"));
                } else if (Dmndi.hasInvalidBounds(shape)) {
                    issues.add(new Issue(Severity.WARNING, local, local == null ? ref : reader.nameOf(local),
                            "Diagram '" + diagram.getAttribute("name")
                                    + "' has a shape with invalid coordinates; it is not shown until you use Auto-layout"));
                }
            }
        }
    }

    private static void collectTypeNames(List<ItemDefinitionView> items, Set<String> names) {
        for (ItemDefinitionView item : items) {
            if (item.name() != null) {
                names.add(item.name());
            }
        }
    }

    private static boolean isKnownType(String typeRef, Set<String> types) {
        String t = typeRef.contains(":") ? typeRef.substring(typeRef.indexOf(':') + 1) : typeRef;
        return BuiltInTypes.isBuiltIn(t) || types.contains(t) || types.contains(typeRef) || typeRef.contains(".");
    }

    private static Issue issue(Severity severity, ElementView e, String message) {
        return new Issue(severity, e.id(), e.displayName(), message);
    }
}
