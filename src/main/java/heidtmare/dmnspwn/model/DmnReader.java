package heidtmare.dmnspwn.model;

import static heidtmare.dmnspwn.xml.DmnDocument.attr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.model.Views.ConnectionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.model.Views.ImportView;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;
import heidtmare.dmnspwn.model.Views.ModelInfo;
import heidtmare.dmnspwn.model.Views.ParameterView;
import heidtmare.dmnspwn.model.Views.RefView;
import heidtmare.dmnspwn.model.Views.ServiceView;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnNamespaces;
import heidtmare.dmnspwn.xml.DmnXml;
import heidtmare.dmnspwn.xml.Href;

/** Builds immutable view models from a {@link DmnDocument}. */
public final class DmnReader {

    private static final List<String> REQUIREMENTS =
            List.of("informationRequirement", "knowledgeRequirement", "authorityRequirement");

    private final DmnDocument doc;
    private final Map<String, Element> nodes = new LinkedHashMap<>();
    private List<ConnectionView> connections;

    public DmnReader(DmnDocument doc) {
        this.doc = doc;
        for (Element e : doc.children(doc.definitions())) {
            if (ElementKind.fromLocalName(e.getLocalName()).isPresent()) {
                String id = e.getAttribute("id");
                if (!id.isEmpty()) {
                    nodes.putIfAbsent(id, e);
                }
            }
        }
    }

    public DmnDocument document() {
        return doc;
    }

    public ModelInfo info() {
        Element d = doc.definitions();
        return new ModelInfo(attr(d, "id"), attr(d, "name"), attr(d, "namespace"), doc.version(), doc.ns(),
                doc.childContent(d, "description"), attr(d, "expressionLanguage"), attr(d, "typeLanguage"),
                attr(d, "exporter"), attr(d, "exporterVersion"), DmnNamespaces.isLatest(doc.ns()),
                doc.supportsDmndi(), DmnNamespaces.supportsBoxedExtensions(doc.ns()));
    }

    /** DRG elements and text annotations, keyed by id, in document order. */
    public Map<String, Element> nodeElements() {
        return nodes;
    }

    public static ElementKind kindOf(Element e) {
        return ElementKind.fromLocalName(e.getLocalName()).orElseThrow();
    }

    public String nameOf(String id) {
        Element e = nodes.get(id);
        if (e == null) {
            return id;
        }
        String name = attr(e, "name");
        if (name == null || name.isBlank()) {
            String text = doc.text(e);
            return text == null || text.isBlank() ? id : text;
        }
        return name;
    }

    public List<ImportView> imports() {
        return doc.children(doc.definitions(), "import").stream()
                .map(i -> new ImportView(attr(i, "name"), attr(i, "namespace"), attr(i, "locationURI"),
                        attr(i, "importType")))
                .toList();
    }

    // ---- connections ---------------------------------------------------------------------------

    public List<ConnectionView> connections() {
        if (connections == null) {
            connections = readConnections();
        }
        return connections;
    }

    private List<ConnectionView> readConnections() {
        String modelNs = doc.modelNamespace();
        List<ConnectionView> result = new ArrayList<>();
        for (Element target : nodes.values()) {
            for (Element req : doc.children(target)) {
                if (!REQUIREMENTS.contains(req.getLocalName())) {
                    continue;
                }
                Optional<Element> refEl = doc.children(req).stream().filter(c -> c.hasAttribute("href")).findFirst();
                if (refEl.isEmpty()) {
                    continue;
                }
                Href href = Href.parse(refEl.get().getAttribute("href"));
                String id = req.getAttribute("id");
                String ref = id.isEmpty()
                        ? target.getAttribute("id") + "|" + req.getLocalName() + "|" + href.id()
                        : id;
                result.add(connection(ref, id, ConnectionKind.fromLocalName(req.getLocalName()).orElseThrow(),
                        href, modelNs, target.getAttribute("id")));
            }
        }
        for (Element assoc : doc.children(doc.definitions(), "association")) {
            Href src = Href.parse(doc.child(assoc, "sourceRef").map(e -> e.getAttribute("href")).orElse(""));
            Href tgt = Href.parse(doc.child(assoc, "targetRef").map(e -> e.getAttribute("href")).orElse(""));
            String id = assoc.getAttribute("id");
            String ref = id.isEmpty() ? "association|" + src.id() + "|" + tgt.id() : id;
            result.add(connection(ref, id, ConnectionKind.ASSOCIATION, src, modelNs, tgt.id()));
        }
        return result;
    }

    private ConnectionView connection(String ref, String id, ConnectionKind kind, Href source, String modelNs,
                                      String targetId) {
        boolean local = source.isLocal(modelNs);
        Element src = local ? nodes.get(source.id()) : null;
        Element tgt = nodes.get(targetId);
        return new ConnectionView(ref, id.isEmpty() ? null : id, kind,
                source.id(), src == null ? source.id() : nameOf(source.id()),
                src == null ? null : kindOf(src), local,
                targetId, nameOf(targetId), tgt == null ? null : kindOf(tgt));
    }

    // ---- elements ------------------------------------------------------------------------------

    public List<ElementView> elements() {
        return nodes.values().stream().map(this::element).toList();
    }

    public Optional<ElementView> element(String id) {
        return Optional.ofNullable(nodes.get(id)).map(this::element);
    }

    private ElementView element(Element e) {
        ElementKind kind = kindOf(e);
        String id = e.getAttribute("id");
        Element variable = doc.child(e, "variable").orElse(null);
        List<ConnectionView> requires = connections().stream()
                .filter(c -> c.targetId().equals(id) && c.kind() != ConnectionKind.ASSOCIATION).toList();
        List<ConnectionView> requiredBy = connections().stream()
                .filter(c -> c.sourceId().equals(id) && c.sourceLocal() || c.kind() == ConnectionKind.ASSOCIATION
                        && (c.sourceId().equals(id) || c.targetId().equals(id)))
                .toList();

        ExpressionView logic = null;
        String functionKind = null;
        List<ParameterView> parameters = List.of();
        if (kind == ElementKind.DECISION) {
            logic = doc.expressionChild(e).map(this::expression).orElse(null);
        } else if (kind == ElementKind.BUSINESS_KNOWLEDGE_MODEL) {
            Optional<Element> fn = doc.child(e, "encapsulatedLogic");
            if (fn.isPresent()) {
                functionKind = attr(fn.get(), "kind");
                parameters = doc.children(fn.get(), "formalParameter").stream()
                        .map(p -> new ParameterView(attr(p, "name"), attr(p, "typeRef"))).toList();
                logic = doc.expressionChild(fn.get()).map(this::expression).orElse(null);
            }
        }
        ServiceView service = kind == ElementKind.DECISION_SERVICE
                ? new ServiceView(refs(e, "outputDecision"), refs(e, "encapsulatedDecision"),
                refs(e, "inputDecision"), refs(e, "inputData"))
                : null;

        return new ElementView(id, kind, attr(e, "name"), attr(e, "label"), doc.childContent(e, "description"),
                variable == null ? null : attr(variable, "typeRef"),
                doc.childContent(e, "question"), doc.childContent(e, "allowedAnswers"),
                kind == ElementKind.TEXT_ANNOTATION ? doc.text(e) : null,
                doc.childContent(e, "type"), attr(e, "locationURI"),
                requires, requiredBy, logic, functionKind, parameters, service);
    }

    private List<RefView> refs(Element parent, String childName) {
        String modelNs = doc.modelNamespace();
        return doc.children(parent, childName).stream().map(r -> {
            Href href = Href.parse(r.getAttribute("href"));
            boolean local = href.isLocal(modelNs) && nodes.containsKey(href.id());
            return new RefView(href.id(), local ? nameOf(href.id()) : href.id(), r.getAttribute("href"), local);
        }).toList();
    }

    // ---- item definitions ----------------------------------------------------------------------

    public List<ItemDefinitionView> itemDefinitions() {
        return itemDefinitions(doc.definitions(), "itemDefinition", "");
    }

    private List<ItemDefinitionView> itemDefinitions(Element parent, String childName, String prefix) {
        List<Element> items = doc.children(parent, childName);
        List<ItemDefinitionView> result = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Element item = items.get(i);
            String path = prefix.isEmpty() ? String.valueOf(i) : prefix + "." + i;
            result.add(new ItemDefinitionView(path, attr(item, "id"), attr(item, "name"), attr(item, "label"),
                    doc.childContent(item, "typeRef"), doc.child(item, "allowedValues").map(doc::text).orElse(null),
                    doc.child(item, "typeConstraint").map(doc::text).orElse(null),
                    "true".equals(item.getAttribute("isCollection")), doc.child(item, "functionItem").isPresent(),
                    itemDefinitions(item, "itemComponent", path)));
        }
        return result;
    }

    // ---- boxed expressions ---------------------------------------------------------------------

    public ExpressionView expression(Element e) {
        if (e == null) {
            return null;
        }
        String id = attr(e, "id");
        String typeRef = attr(e, "typeRef");
        return switch (e.getLocalName()) {
            case "literalExpression" -> literal(e);
            case "decisionTable" -> decisionTable(e);
            case "context" -> new ExpressionView.Context(id, typeRef, doc.children(e, "contextEntry").stream()
                    .map(entry -> {
                        Element v = doc.child(entry, "variable").orElse(null);
                        return new ExpressionView.ContextEntry(attr(v, "name"), attr(v, "typeRef"),
                                childExpression(entry));
                    }).toList());
            case "relation" -> new ExpressionView.Relation(id, typeRef,
                    doc.children(e, "column").stream()
                            .map(c -> new ExpressionView.Column(attr(c, "name"), attr(c, "typeRef"))).toList(),
                    doc.children(e, "row").stream()
                            .map(r -> doc.expressionChildren(r).stream().map(this::expression).toList()).toList());
            case "list" -> new ExpressionView.ListExpr(id, typeRef,
                    doc.expressionChildren(e).stream().map(this::expression).toList());
            case "invocation" -> {
                ExpressionView callee = childExpression(e);
                String fn = callee instanceof ExpressionView.Literal l ? l.text() : callee == null ? "" : "…";
                yield new ExpressionView.Invocation(id, typeRef, fn, doc.children(e, "binding").stream()
                        .map(b -> {
                            Element p = doc.child(b, "parameter").orElse(null);
                            return new ExpressionView.Binding(attr(p, "name"), attr(p, "typeRef"),
                                    childExpression(b));
                        }).toList());
            }
            case "functionDefinition" -> new ExpressionView.Function(id, typeRef, attr(e, "kind"),
                    doc.children(e, "formalParameter").stream()
                            .map(p -> new ExpressionView.Parameter(attr(p, "name"), attr(p, "typeRef"))).toList(),
                    childExpression(e));
            case "conditional" -> new ExpressionView.Conditional(id, typeRef, nested(e, "if"), nested(e, "then"),
                    nested(e, "else"));
            case "for", "some", "every" -> new ExpressionView.Iterator(id, typeRef, e.getLocalName(),
                    attr(e, "iteratorVariable"), nested(e, "in"),
                    "for".equals(e.getLocalName()) ? "return" : "satisfies",
                    nested(e, "for".equals(e.getLocalName()) ? "return" : "satisfies"));
            case "filter" -> new ExpressionView.Filter(id, typeRef, nested(e, "in"), nested(e, "match"));
            default -> new ExpressionView.Unknown(id, typeRef, e.getLocalName(), DmnXml.serialize(
                    (Element) e.cloneNode(true)));
        };
    }

    private ExpressionView.Literal literal(Element e) {
        String text = doc.text(e);
        return new ExpressionView.Literal(attr(e, "id"), attr(e, "typeRef"), text == null ? "" : text,
                attr(e, "expressionLanguage"));
    }

    private ExpressionView childExpression(Element parent) {
        return doc.expressionChild(parent).map(this::expression).orElse(null);
    }

    private ExpressionView nested(Element parent, String childName) {
        return doc.child(parent, childName).map(this::childExpression).orElse(null);
    }

    private ExpressionView.DecisionTable decisionTable(Element e) {
        List<ExpressionView.InputClause> inputs = doc.children(e, "input").stream().map(in -> {
            Element expr = doc.child(in, "inputExpression").orElse(null);
            return new ExpressionView.InputClause(attr(in, "id"), attr(in, "label"), orEmpty(doc.text(expr)),
                    attr(expr, "typeRef"), doc.child(in, "inputValues").map(doc::text).orElse(null));
        }).toList();
        List<ExpressionView.OutputClause> outputs = doc.children(e, "output").stream()
                .map(out -> new ExpressionView.OutputClause(attr(out, "id"), attr(out, "name"), attr(out, "label"),
                        attr(out, "typeRef"), doc.child(out, "outputValues").map(doc::text).orElse(null),
                        doc.child(out, "defaultOutputEntry").map(doc::text).orElse(null)))
                .toList();
        List<String> annotations = doc.children(e, "annotation").stream().map(a -> orEmpty(attr(a, "name"))).toList();
        List<ExpressionView.Rule> rules = doc.children(e, "rule").stream()
                .map(r -> new ExpressionView.Rule(attr(r, "id"), texts(r, "inputEntry"), texts(r, "outputEntry"),
                        texts(r, "annotationEntry"), doc.childContent(r, "description")))
                .toList();
        return new ExpressionView.DecisionTable(attr(e, "id"), attr(e, "typeRef"), attr(e, "hitPolicy"),
                attr(e, "aggregation"), attr(e, "outputLabel"), attr(e, "preferredOrientation"), inputs, outputs,
                annotations, rules);
    }

    private List<String> texts(Element parent, String childName) {
        return doc.children(parent, childName).stream().map(c -> orEmpty(doc.text(c))).toList();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
