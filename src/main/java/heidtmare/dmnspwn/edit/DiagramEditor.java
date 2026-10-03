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
import heidtmare.dmnspwn.diagram.DiagramBuilder;
import heidtmare.dmnspwn.diagram.Geometry;
import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.edit.ConnectionElements.Conn;
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

    public void addShape(Element diagram, String elementId, ElementKind kind, Bounds at) {
        if (DiagramBuilder.shapesByElement(doc, diagram).containsKey(elementId)) {
            return;
        }
        Bounds b = at != null ? at : freeSlot(diagram, kind);
        diagram.appendChild(shape(elementId, kind, b));
        syncEdges(diagram);
    }

    public void removeShape(Element diagram, String elementId) {
        Element shape = DiagramBuilder.shapesByElement(doc, diagram).get(elementId);
        if (shape == null) {
            return;
        }
        DmnDocument.remove(shape);
        Set<String> connIds = new HashSet<>();
        for (Conn c : ConnectionElements.all(doc)) {
            if (elementId.equals(c.sourceId()) || elementId.equals(c.targetId())) {
                connIds.add(c.element().getAttribute("id"));
            }
        }
        removeEdges(diagram, connIds);
    }

    /** Moves (and optionally resizes) a shape; decision services carry their contents along. */
    public void move(Element diagram, String elementId, double x, double y, Double width, Double height) {
        Map<String, Element> shapes = DiagramBuilder.shapesByElement(doc, diagram);
        Element shape = shapes.get(elementId);
        Element element = doc.findById(elementId)
                .orElseThrow(() -> new DmnEditException("Unknown element " + elementId));
        ElementKind kind = DmnReader.kindOf(element);
        if (shape == null) {
            addShape(diagram, elementId, kind, new Bounds(x, y, kind.width(), kind.height()));
            return;
        }
        Bounds old = DiagramBuilder.bounds(shape).orElse(new Bounds(x, y, kind.width(), kind.height()));
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
                    DiagramBuilder.bounds(other).filter(old::contains).ifPresent(b -> {
                        setBounds(other, b.moveTo(b.x() + dx, b.y() + dy));
                        touched.add(id);
                    });
                }
            });
            anyChildren(shape, "DMNDecisionServiceDividerLine").forEach(line -> {
                List<Point> pts = DiagramBuilder.waypoints(line);
                double dividerY = pts.isEmpty() ? moved.cy() : pts.getFirst().y() + dy;
                dividerY = Math.min(moved.bottom() - 10, Math.max(moved.y() + 10, dividerY));
                setWaypoints(line, new Point(moved.x(), dividerY), new Point(moved.right(), dividerY));
            });
        }
        reroute(diagram, touched);
    }

    /** Re-applies the automatic layout to every shape on the diagram. */
    public void resetLayout(Element diagram) {
        Map<String, Bounds> layout = AutoLayout.layout(new DmnReader(doc));
        DiagramBuilder.shapesByElement(doc, diagram).forEach((id, shape) -> {
            Bounds b = layout.get(id);
            if (b != null) {
                setBounds(shape, b);
                anyChildren(shape, "DMNDecisionServiceDividerLine").forEach(line ->
                        setWaypoints(line, new Point(b.x(), b.cy()), new Point(b.right(), b.cy())));
            }
        });
        syncEdges(diagram);
        reroute(diagram, null);
    }

    /** Adds missing edges for every connection whose ends are both on the diagram. */
    public void syncEdges(Element diagram) {
        Map<String, Element> shapes = DiagramBuilder.shapesByElement(doc, diagram);
        Set<String> existing = new HashSet<>();
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String ref = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            if (ref != null) {
                existing.add(ref);
            }
        }
        for (Conn c : ConnectionElements.all(doc)) {
            Element s = shapes.get(c.sourceId());
            Element t = shapes.get(c.targetId());
            if (s == null || t == null) {
                continue;
            }
            String id = doc.ensureId(c.element(), idPrefix(c.element().getLocalName()));
            if (existing.add(id)) {
                Point[] pts = Geometry.connect(DiagramBuilder.bounds(s).orElseThrow(),
                        DiagramBuilder.bounds(t).orElseThrow());
                Element edge = doc.createDi(doc.dmndiNs(), "DMNEdge");
                edge.setAttribute("id", doc.uniqueId("DMNEdge"));
                edge.setAttribute("dmnElementRef", id);
                setWaypoints(edge, pts);
                diagram.appendChild(edge);
            }
        }
    }

    /** Adds edges in every diagram for a newly created connection. */
    public void addEdgesForNewConnection() {
        doc.diagrams().forEach(this::syncEdges);
    }

    public void removeEdges(Element diagram, Set<String> connectionIds) {
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String ref = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            if (ref != null && connectionIds.contains(ref)) {
                DmnDocument.remove(edge);
            }
        }
    }

    /** Removes all DMNDI for an element (shapes) and the given connections (edges) in all diagrams. */
    public void purge(String elementId, Set<String> connectionIds) {
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
        Map<String, Element> shapes = DiagramBuilder.shapesByElement(doc, diagram);
        Map<String, Conn> byId = new HashMap<>();
        for (Conn c : ConnectionElements.all(doc)) {
            String id = c.element().getAttribute("id");
            if (!id.isEmpty()) {
                byId.put(id, c);
            }
        }
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String ref = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            Conn c = ref == null ? null : byId.get(ref);
            if (c == null || touched != null && !touched.contains(c.sourceId()) && !touched.contains(c.targetId())) {
                continue;
            }
            Element s = shapes.get(c.sourceId());
            Element t = shapes.get(c.targetId());
            if (s != null && t != null) {
                setWaypoints(edge, Geometry.connect(DiagramBuilder.bounds(s).orElseThrow(),
                        DiagramBuilder.bounds(t).orElseThrow()));
            }
        }
    }

    private Element shape(String elementId, ElementKind kind, Bounds b) {
        Element shape = doc.createDi(doc.dmndiNs(), "DMNShape");
        shape.setAttribute("id", doc.uniqueId("DMNShape"));
        shape.setAttribute("dmnElementRef", elementId);
        if (kind == ElementKind.DECISION_SERVICE) {
            shape.setAttribute("isCollapsed", "false");
        }
        Element bounds = doc.createDi(DmnNamespaces.DC, "Bounds");
        shape.appendChild(bounds);
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
            Optional<Bounds> b = DiagramBuilder.bounds(shape);
            if (b.isPresent()) {
                right = Double.isNaN(right) ? b.get().right() : Math.max(right, b.get().right());
                top = Double.isNaN(top) ? b.get().y() : Math.min(top, b.get().y());
            }
        }
        double x = Double.isNaN(right) ? 40 : right + 50;
        double y = Double.isNaN(top) ? 40 : top;
        return new Bounds(x, y, kind.width(), kind.height());
    }

    private static void setBounds(Element shape, Bounds b) {
        Element bounds = anyChildren(shape, "Bounds").stream().findFirst().orElseThrow();
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

    static String idPrefix(String localName) {
        return Character.toUpperCase(localName.charAt(0)) + localName.substring(1);
    }
}
