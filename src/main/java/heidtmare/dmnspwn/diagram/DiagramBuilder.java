package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.xml.DmnDocument.anyChildren;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.DiagramView.DiagramRef;
import heidtmare.dmnspwn.diagram.DiagramView.Edge;
import heidtmare.dmnspwn.diagram.DiagramView.Node;
import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.Views.ConnectionView;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Turns DMNDI (or an automatic layout when there is none) into a {@link DiagramView}. */
public final class DiagramBuilder {

    private static final double PAD = 30;

    private DiagramBuilder() {
    }

    public static List<DiagramRef> diagrams(DmnDocument doc) {
        List<DiagramRef> refs = new ArrayList<>();
        List<Element> diagrams = doc.diagrams();
        for (int i = 0; i < diagrams.size(); i++) {
            Element d = diagrams.get(i);
            String name = d.getAttribute("name");
            refs.add(new DiagramRef(d.getAttribute("id"), name.isBlank() ? "DRD " + (i + 1) : name));
        }
        return refs;
    }

    public static DiagramView build(DmnReader reader, String diagramId) {
        Optional<Element> diagram = reader.document().diagram(diagramId);
        return diagram.isPresent() ? fromDmndi(reader, diagram.get()) : automatic(reader);
    }

    // ---- DMNDI ---------------------------------------------------------------------------------

    public static Optional<Bounds> bounds(Element shape) {
        return anyChildren(shape, "Bounds").stream().findFirst().map(b -> new Bounds(
                num(b, "x"), num(b, "y"), num(b, "width"), num(b, "height")));
    }

    public static List<Point> waypoints(Element owner) {
        return anyChildren(owner, "waypoint").stream().map(w -> new Point(num(w, "x"), num(w, "y"))).toList();
    }

    /** Local element id to its (first) shape in the diagram. */
    public static Map<String, Element> shapesByElement(DmnDocument doc, Element diagram) {
        Map<String, Element> result = new LinkedHashMap<>();
        for (Element shape : anyChildren(diagram, "DMNShape")) {
            String id = doc.localIdOfRef(shape, shape.getAttribute("dmnElementRef"));
            if (id != null) {
                result.putIfAbsent(id, shape);
            }
        }
        return result;
    }

    private static DiagramView fromDmndi(DmnReader reader, Element diagram) {
        DmnDocument doc = reader.document();
        Map<String, Element> elements = reader.nodeElements();
        Map<String, Bounds> placed = new HashMap<>();
        List<Node> nodes = new ArrayList<>();
        for (Element shape : anyChildren(diagram, "DMNShape")) {
            Optional<Bounds> b = bounds(shape);
            if (b.isEmpty()) {
                continue;
            }
            String ref = shape.getAttribute("dmnElementRef");
            String localId = doc.localIdOfRef(shape, ref);
            Element element = localId == null ? null : elements.get(localId);
            if (element == null) {
                nodes.add(node(ref, shape.getAttribute("id"), null, ref, b.get(), Double.NaN, true));
                continue;
            }
            double divider = anyChildren(shape, "DMNDecisionServiceDividerLine").stream().findFirst()
                    .map(DiagramBuilder::waypoints).filter(p -> !p.isEmpty()).map(p -> p.getFirst().y())
                    .orElse(Double.NaN);
            nodes.add(node(localId, shape.getAttribute("id"), DmnReader.kindOf(element), reader.nameOf(localId),
                    b.get(), divider, false));
            placed.putIfAbsent(localId, b.get());
        }

        Map<String, ConnectionView> byId = new HashMap<>();
        reader.connections().stream().filter(c -> c.id() != null).forEach(c -> byId.put(c.id(), c));
        Set<String> drawn = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        for (Element edge : anyChildren(diagram, "DMNEdge")) {
            String localId = doc.localIdOfRef(edge, edge.getAttribute("dmnElementRef"));
            ConnectionView c = localId == null ? null : byId.get(localId);
            if (c == null) {
                continue;
            }
            List<Point> points = waypoints(edge);
            if (points.size() < 2) {
                Bounds s = placed.get(c.sourceId());
                Bounds t = placed.get(c.targetId());
                if (s == null || t == null) {
                    continue;
                }
                points = List.of(Geometry.connect(s, t));
            }
            edges.add(new Edge(c.ref(), c.kind(), c.sourceId(), c.targetId(), points));
            drawn.add(c.ref());
        }
        // Requirements without DMNEdge are still drawn when both ends are on this diagram.
        for (ConnectionView c : reader.connections()) {
            if (!drawn.contains(c.ref()) && placed.containsKey(c.sourceId()) && placed.containsKey(c.targetId())) {
                edges.add(new Edge(c.ref(), c.kind(), c.sourceId(), c.targetId(),
                        List.of(Geometry.connect(placed.get(c.sourceId()), placed.get(c.targetId())))));
            }
        }
        String name = diagram.getAttribute("name");
        return new DiagramView(diagram.getAttribute("id"), name.isBlank() ? "DRD" : name, viewport(nodes, edges),
                nodes, edges, true);
    }

    // ---- automatic -----------------------------------------------------------------------------

    private static DiagramView automatic(DmnReader reader) {
        Map<String, Bounds> layout = AutoLayout.layout(reader);
        Map<String, Element> elements = reader.nodeElements();
        List<Node> nodes = new ArrayList<>();
        layout.forEach((id, b) -> nodes.add(node(id, null, DmnReader.kindOf(elements.get(id)), reader.nameOf(id), b,
                Double.NaN, false)));
        List<Edge> edges = new ArrayList<>();
        for (ConnectionView c : reader.connections()) {
            Bounds s = layout.get(c.sourceId());
            Bounds t = layout.get(c.targetId());
            if (s != null && t != null && (c.sourceLocal() || c.kind() == heidtmare.dmnspwn.model.ConnectionKind.ASSOCIATION)) {
                edges.add(new Edge(c.ref(), c.kind(), c.sourceId(), c.targetId(), List.of(Geometry.connect(s, t))));
            }
        }
        return new DiagramView(null, "Automatic layout", viewport(nodes, edges), nodes, edges, false);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Node node(String id, String shapeId, ElementKind kind, String name, Bounds b, double dividerY,
                             boolean external) {
        String divider = null;
        if (kind == ElementKind.DECISION_SERVICE) {
            double y = Double.isNaN(dividerY) ? b.cy() : dividerY;
            divider = Shapes.divider(b, y);
        }
        String anchor = kind == ElementKind.TEXT_ANNOTATION ? "start" : "middle";
        return new Node(id, shapeId, kind, name, b, Shapes.label(kind, name, b), anchor, Shapes.path(kind, b),
                divider, external);
    }

    private static Bounds viewport(List<Node> nodes, List<Edge> edges) {
        if (nodes.isEmpty()) {
            return new Bounds(0, 0, 480, 160);
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Node n : nodes) {
            minX = Math.min(minX, n.bounds().x());
            minY = Math.min(minY, n.bounds().y());
            maxX = Math.max(maxX, n.bounds().right());
            maxY = Math.max(maxY, n.bounds().bottom());
        }
        for (Edge e : edges) {
            for (Point p : e.points()) {
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
            }
        }
        return new Bounds(minX - PAD, minY - PAD, maxX - minX + 2 * PAD, maxY - minY + 2 * PAD);
    }

    private static double num(Element e, String attr) {
        try {
            return Double.parseDouble(e.getAttribute(attr));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
