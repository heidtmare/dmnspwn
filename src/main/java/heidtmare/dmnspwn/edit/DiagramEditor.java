package heidtmare.dmnspwn.edit;

import static heidtmare.dmnspwn.xml.DmnDocument.anyChildren;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.AutoLayout;
import heidtmare.dmnspwn.diagram.Dmndi;
import heidtmare.dmnspwn.diagram.Geometry;
import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.model.ConnectionElement;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnNamespaces;

/** Edits DMNDI: shapes, edges and their positions. */
public final class DiagramEditor {

    private static final double MIN_WIDTH = 60;
    private static final double MIN_HEIGHT = 30;

    private final DmnDocument doc;

    public DiagramEditor(DmnDocument doc) {
        this.doc = doc;
    }

    /**
     * Returns the requested (or first) diagram, materialising the automatic layout as DMNDI
     * when the model has no diagram yet.
     */
    public Element ensureDiagram(String diagramId) {
        if (!doc.supportsDmndi()) {
            throw new DmnEditException(
                    "DMN 1.1 has no diagram interchange (DMNDI). Convert the model to DMN 1.5 to edit its layout.");
        }
        Optional<Element> existing = doc.diagram(diagramId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Map<String, Bounds> layout = AutoLayout.layout(new DmnReader(doc));
        Element diagram = doc.createDi(doc.dmndiNs(), "DMNDiagram");
        diagram.setAttribute("id", doc.uniqueId("DRD"));
        diagram.setAttribute("name", "Decision Requirements Diagram");
        doc.dmndiOrCreate().appendChild(diagram);
        DmnReader reader = new DmnReader(doc);
        layout.forEach((id, b) -> diagram.appendChild(shape(id, DmnReader.kindOf(reader.nodeElements().get(id)), b)));
        syncEdges(diagram);
        return diagram;
    }

    /** Puts an element on a diagram (the first one when no id is given) and returns the diagram's id. */
    public String addShape(String diagramId, String elementId) {
        Element diagram = ensureDiagram(diagramId);
        addShape(diagram, elementId, DmnReader.kindOf(EditSupport.node(doc, elementId)), null);
        return diagram.getAttribute("id");
    }

    void addShape(Element diagram, String elementId, ElementKind kind, Bounds at) {
        if (Dmndi.shapesByElement(doc, diagram).containsKey(elementId)) {
            return;
        }
        Bounds b = at != null ? at : freeSlot(diagram, kind);
        diagram.appendChild(shape(elementId, kind, b));
        syncEdges(diagram);
    }

    /** Takes an element off a diagram (the first one when no id is given) and returns the diagram's id. */
    public String removeShape(String diagramId, String elementId) {
        Element diagram = ensureDiagram(diagramId);
        removeShape(diagram, elementId);
        return diagram.getAttribute("id");
    }

    private void removeShape(Element diagram, String elementId) {
        Element shape = Dmndi.shapesByElement(doc, diagram).get(elementId);
        if (shape == null) {
            return;
        }
        DmnDocument.remove(shape);
        Set<String> connIds = new HashSet<>();
        for (ConnectionElement c : ConnectionElement.all(doc)) {
            if (c.touches(elementId)) {
                connIds.add(c.id());
            }
        }
        removeEdges(diagram, connIds);
    }

    /** Moves (and optionally resizes) a shape; decision services carry their contents along. */
    public void move(String diagramId, String elementId, double x, double y, Double width, Double height) {
        Element diagram = ensureDiagram(diagramId);
        Map<String, Element> shapes = Dmndi.shapesByElement(doc, diagram);
        Element shape = shapes.get(elementId);
        Element element = doc.findById(elementId)
                .orElseThrow(() -> new DmnEditException("Unknown element " + elementId));
        ElementKind kind = DmnReader.kindOf(element);
        if (shape == null) {
            addShape(diagram, elementId, kind, new Bounds(x, y, kind.width(), kind.height()));
            return;
        }
        Bounds old = Dmndi.bounds(shape).orElse(new Bounds(x, y, kind.width(), kind.height()));
        Bounds moved = new Bounds(Geometry.round(x), Geometry.round(y),
                Math.max(MIN_WIDTH, width == null ? old.width() : width),
                Math.max(MIN_HEIGHT, height == null ? old.height() : height));
        setBounds(shape, moved);
        Set<String> touched = new HashSet<>(Set.of(elementId));
        if (kind == ElementKind.DECISION_SERVICE) {
            double dx = moved.x() - old.x();
            double dy = moved.y() - old.y();
            shapes.forEach((id, other) -> {
                if (other != shape) {
                    Dmndi.bounds(other).filter(old::contains).ifPresent(b -> {
                        setBounds(other, b.moveTo(b.x() + dx, b.y() + dy));
                        touched.add(id);
                    });
                }
            });
            anyChildren(shape, "DMNDecisionServiceDividerLine").forEach(line -> {
                List<Point> pts = Dmndi.waypoints(line);
                double dividerY = pts.isEmpty() ? moved.cy() : pts.getFirst().y() + dy;
                dividerY = Math.min(moved.bottom() - 10, Math.max(moved.y() + 10, dividerY));
                setWaypoints(line, new Point(moved.x(), dividerY), new Point(moved.right(), dividerY));
            });
        }
        reroute(diagram, touched);
    }

    /** Re-applies the automatic layout to every shape on a diagram (the first one when no id is given). */
    public String resetLayout(String diagramId) {
        Element diagram = ensureDiagram(diagramId);
        Map<String, Bounds> layout = AutoLayout.layout(new DmnReader(doc));
        Dmndi.shapesByElement(doc, diagram).forEach((id, shape) -> {
            Bounds b = layout.get(id);
            if (b != null) {
                setBounds(shape, b);
                anyChildren(shape, "DMNDecisionServiceDividerLine").forEach(line ->
                        setWaypoints(line, new Point(b.x(), b.cy()), new Point(b.right(), b.cy())));
            }
        });
        syncEdges(diagram);
        reroute(diagram, null);
        return diagram.getAttribute("id");
    }

    /** Adds missing edges for every connection whose ends are both on the diagram. */
    private void syncEdges(Element diagram) {
        Map<String, Element> shapes = Dmndi.shapesByElement(doc, diagram);
        Set<String> existing = new HashSet<>();
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String ref = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            if (ref != null) {
                existing.add(ref);
            }
        }
        for (ConnectionElement c : ConnectionElement.all(doc)) {
            Element s = shapes.get(c.sourceId());
            Element t = shapes.get(c.targetId());
            if (s == null || t == null) {
                continue;
            }
            String id = doc.ensureId(c.element(), DmnDocument.idPrefix(c.element().getLocalName()));
            Optional<Point[]> pts = connect(s, t);
            if (pts.isPresent() && existing.add(id)) {
                Element edge = doc.createDi(doc.dmndiNs(), "DMNEdge");
                edge.setAttribute("id", doc.uniqueId("DMNEdge"));
                edge.setAttribute("dmnElementRef", id);
                setWaypoints(edge, pts.get());
                diagram.appendChild(edge);
            }
        }
    }

    /** Adds edges in every diagram for a newly created connection. */
    void addEdgesForNewConnection() {
        doc.diagrams().forEach(this::syncEdges);
    }

    private void removeEdges(Element diagram, Set<String> connectionIds) {
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String ref = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            if (ref != null && connectionIds.contains(ref)) {
                DmnDocument.remove(edge);
            }
        }
    }

    /** Removes all DMNDI for an element (shapes) and the given connections (edges) in all diagrams. */
    void purge(String elementId, Set<String> connectionIds) {
        for (Element diagram : doc.diagrams()) {
            for (Element shape : anyChildren(diagram, "DMNShape")) {
                if (elementId != null
                        && elementId.equals(doc.localIdOfRef(shape, shape.getAttribute("dmnElementRef")))) {
                    DmnDocument.remove(shape);
                }
            }
            removeEdges(diagram, connectionIds);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void reroute(Element diagram, Set<String> touched) {
        Map<String, Element> shapes = Dmndi.shapesByElement(doc, diagram);
        Map<String, ConnectionElement> byId = new HashMap<>();
        for (ConnectionElement c : ConnectionElement.all(doc)) {
            if (c.id() != null) {
                byId.put(c.id(), c);
            }
        }
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String ref = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            ConnectionElement c = ref == null ? null : byId.get(ref);
            if (c == null || touched != null && !touched.contains(c.sourceId()) && !touched.contains(c.targetId())) {
                continue;
            }
            Element s = shapes.get(c.sourceId());
            Element t = shapes.get(c.targetId());
            if (s != null && t != null) {
                connect(s, t).ifPresent(pts -> setWaypoints(edge, pts));
            }
        }
    }

    /** The waypoints joining two shapes, unless either has unreadable bounds. */
    private static Optional<Point[]> connect(Element source, Element target) {
        return Dmndi.bounds(source).flatMap(s -> Dmndi.bounds(target).map(t -> Geometry.connect(s, t)));
    }

    private Element shape(String elementId, ElementKind kind, Bounds b) {
        Element shape = doc.createDi(doc.dmndiNs(), "DMNShape");
        shape.setAttribute("id", doc.uniqueId("DMNShape"));
        shape.setAttribute("dmnElementRef", elementId);
        if (kind == ElementKind.DECISION_SERVICE) {
            shape.setAttribute("isCollapsed", "false");
        }
        setBounds(shape, b);
        if (kind == ElementKind.DECISION_SERVICE) {
            Element line = doc.createDi(doc.dmndiNs(), "DMNDecisionServiceDividerLine");
            shape.appendChild(line);
            setWaypoints(line, new Point(b.x(), b.cy()), new Point(b.right(), b.cy()));
        }
        return shape;
    }

    private Bounds freeSlot(Element diagram, ElementKind kind) {
        double right = Double.NaN;
        double top = Double.NaN;
        for (Element shape : anyChildren(diagram, "DMNShape")) {
            Optional<Bounds> b = Dmndi.bounds(shape);
            if (b.isPresent()) {
                right = Double.isNaN(right) ? b.get().right() : Math.max(right, b.get().right());
                top = Double.isNaN(top) ? b.get().y() : Math.min(top, b.get().y());
            }
        }
        double x = Double.isNaN(right) ? 40 : right + 50;
        double y = Double.isNaN(top) ? 40 : top;
        return new Bounds(x, y, kind.width(), kind.height());
    }

    /** Writes a shape's bounds, adding the {@code Bounds} element (before label and divider) when it is missing. */
    private void setBounds(Element shape, Bounds b) {
        Element bounds = anyChildren(shape, "Bounds").stream().findFirst().orElseGet(() -> {
            Element created = doc.createDi(DmnNamespaces.DC, "Bounds");
            Element next = anyChildren(shape, "DMNLabel").stream().findFirst()
                    .or(() -> anyChildren(shape, "DMNDecisionServiceDividerLine").stream().findFirst()).orElse(null);
            shape.insertBefore(created, next);
            return created;
        });
        bounds.setAttribute("x", Geometry.fmt(b.x()));
        bounds.setAttribute("y", Geometry.fmt(b.y()));
        bounds.setAttribute("width", Geometry.fmt(b.width()));
        bounds.setAttribute("height", Geometry.fmt(b.height()));
    }

    private void setWaypoints(Element owner, Point... points) {
        anyChildren(owner, "waypoint").forEach(DmnDocument::remove);
        Element label = anyChildren(owner, "DMNLabel").stream().findFirst().orElse(null);
        for (Point p : points) {
            Element wp = doc.createDi(DmnNamespaces.DI, "waypoint");
            wp.setAttribute("x", Geometry.fmt(p.x()));
            wp.setAttribute("y", Geometry.fmt(p.y()));
            owner.insertBefore(wp, label); // waypoints precede an edge's DMNLabel
        }
    }
}
