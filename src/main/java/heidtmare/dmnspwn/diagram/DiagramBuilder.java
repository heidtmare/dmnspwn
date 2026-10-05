package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.diagram.Geometry.fmt;
import static heidtmare.dmnspwn.xml.DmnDocument.anyChildren;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.DiagramView.DiagramRef;
import heidtmare.dmnspwn.diagram.DiagramView.Edge;
import heidtmare.dmnspwn.diagram.DiagramView.Line;
import heidtmare.dmnspwn.diagram.DiagramView.Node;
import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.model.ConnectionKind;
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

    private static DiagramView fromDmndi(DmnReader reader, Element diagram) {
        DmnDocument doc = reader.document();
        Map<String, Element> elements = reader.nodeElements();
        Map<String, Bounds> placed = new HashMap<>();
        List<Node> nodes = new ArrayList<>();
        for (Element shape : anyChildren(diagram, "DMNShape")) {
            Optional<Bounds> b = Dmndi.bounds(shape);
            if (b.isEmpty()) {
                continue;
            }
            String ref = shape.getAttribute("dmnElementRef");
            String localId = doc.localIdOfRef(shape, ref);
            Element element = localId == null ? null : elements.get(localId);
            if (element == null) {
                nodes.add(node(ref, null, ref, b.get(), Double.NaN, true));
                continue;
            }
            double divider = anyChildren(shape, "DMNDecisionServiceDividerLine").stream().findFirst()
                    .map(Dmndi::waypoints).filter(p -> !p.isEmpty()).map(p -> p.getFirst().y())
                    .orElse(Double.NaN);
            nodes.add(node(localId, DmnReader.kindOf(element), reader.nameOf(localId),
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
            List<Point> points = Dmndi.waypoints(edge);
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
        layout.forEach((id, b) -> nodes.add(node(id, DmnReader.kindOf(elements.get(id)), reader.nameOf(id), b,
                Double.NaN, false)));
        List<Edge> edges = new ArrayList<>();
        for (ConnectionView c : reader.connections()) {
            Bounds s = layout.get(c.sourceId());
            Bounds t = layout.get(c.targetId());
            if (s != null && t != null && (c.sourceLocal() || c.kind() == ConnectionKind.ASSOCIATION)) {
                edges.add(new Edge(c.ref(), c.kind(), c.sourceId(), c.targetId(), List.of(Geometry.connect(s, t))));
            }
        }
        return new DiagramView(null, "Automatic layout", viewport(nodes, edges), nodes, edges, false);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Node node(String id, ElementKind kind, String name, Bounds b, double dividerY,
                             boolean external) {
        String divider = null;
        if (kind == ElementKind.DECISION_SERVICE) {
            double y = Double.isNaN(dividerY) ? b.cy() : dividerY;
            divider = Shapes.divider(b, y);
        }
        String anchor = kind == ElementKind.TEXT_ANNOTATION ? "start" : "middle";
        return new Node(id, kind, name, b, Shapes.label(kind, name, b), anchor, Shapes.path(kind, b),
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

    /** SVG path data for the DMN notation shapes, plus label wrapping. */
    static final class Shapes {

        private static final double CHAR_WIDTH = 7.0;
        private static final double LINE_HEIGHT = 16;

        private Shapes() {
        }

        static String path(ElementKind kind, Bounds b) {
            double x = b.x();
            double y = b.y();
            double w = b.width();
            double h = b.height();
            if (kind == null) {
                return rect(x, y, w, h);
            }
            return switch (kind) {
                case DECISION -> rect(x, y, w, h);
                case INPUT_DATA -> {
                    double r = Math.min(h / 2, w / 2);
                    yield "M" + fmt(x + r) + " " + fmt(y) + " H" + fmt(x + w - r)
                            + " A" + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(x + w - r) + " " + fmt(y + h)
                            + " H" + fmt(x + r)
                            + " A" + fmt(r) + " " + fmt(r) + " 0 0 1 " + fmt(x + r) + " " + fmt(y) + " Z";
                }
                case BUSINESS_KNOWLEDGE_MODEL -> {
                    double c = Math.min(15, Math.min(w, h) / 4);
                    yield "M" + fmt(x + c) + " " + fmt(y) + " H" + fmt(x + w) + " V" + fmt(y + h - c)
                            + " L" + fmt(x + w - c) + " " + fmt(y + h) + " H" + fmt(x) + " V" + fmt(y + c) + " Z";
                }
                case KNOWLEDGE_SOURCE -> {
                    double a = h * 0.1;
                    yield "M" + fmt(x) + " " + fmt(y) + " H" + fmt(x + w) + " V" + fmt(y + h - a)
                            + " Q" + fmt(x + w * 0.75) + " " + fmt(y + h - 3 * a) + " " + fmt(x + w * 0.5) + " "
                            + fmt(y + h - a)
                            + " T" + fmt(x) + " " + fmt(y + h - a) + " Z";
                }
                case DECISION_SERVICE -> {
                    double r = Math.min(15, Math.min(w, h) / 4);
                    yield "M" + fmt(x + r) + " " + fmt(y) + " H" + fmt(x + w - r)
                            + " Q" + fmt(x + w) + " " + fmt(y) + " " + fmt(x + w) + " " + fmt(y + r)
                            + " V" + fmt(y + h - r)
                            + " Q" + fmt(x + w) + " " + fmt(y + h) + " " + fmt(x + w - r) + " " + fmt(y + h)
                            + " H" + fmt(x + r)
                            + " Q" + fmt(x) + " " + fmt(y + h) + " " + fmt(x) + " " + fmt(y + h - r)
                            + " V" + fmt(y + r)
                            + " Q" + fmt(x) + " " + fmt(y) + " " + fmt(x + r) + " " + fmt(y) + " Z";
                }
                case TEXT_ANNOTATION -> "M" + fmt(x + 20) + " " + fmt(y) + " H" + fmt(x) + " V" + fmt(y + h)
                        + " H" + fmt(x + 20);
            };
        }

        private static String rect(double x, double y, double w, double h) {
            return "M" + fmt(x) + " " + fmt(y) + " H" + fmt(x + w) + " V" + fmt(y + h) + " H" + fmt(x) + " Z";
        }

        static String divider(Bounds b, double dividerY) {
            return "M" + fmt(b.x()) + " " + fmt(dividerY) + " H" + fmt(b.right());
        }

        /** Wraps a label into lines positioned inside the shape. */
        static List<Line> label(ElementKind kind, String text, Bounds b) {
            boolean annotation = kind == ElementKind.TEXT_ANNOTATION;
            boolean service = kind == ElementKind.DECISION_SERVICE;
            double inset = kind == ElementKind.INPUT_DATA ? Math.min(b.height() / 2, 24) : 10;
            int maxChars = Math.max(4, (int) ((b.width() - 2 * inset) / CHAR_WIDTH));
            int maxLines = service ? 2 : Math.max(1, (int) ((b.height() - 8) / LINE_HEIGHT));
            List<String> lines = wrap(text == null ? "" : text, maxChars, maxLines);

            double x = annotation ? b.x() + 8 : b.cx();
            double firstY = service
                    ? b.y() + 20
                    : b.cy() - (lines.size() - 1) * LINE_HEIGHT / 2 + 4.5;
            List<Line> result = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                result.add(new Line(lines.get(i), fmt(x), fmt(firstY + i * LINE_HEIGHT)));
            }
            return result;
        }

        static List<String> wrap(String text, int maxChars, int maxLines) {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.strip().split("\\s+")) {
                while (word.length() > maxChars) {
                    if (!current.isEmpty()) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                    lines.add(word.substring(0, maxChars));
                    word = word.substring(maxChars);
                }
                if (current.isEmpty()) {
                    current.append(word);
                } else if (current.length() + 1 + word.length() <= maxChars) {
                    current.append(' ').append(word);
                } else {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            if (lines.size() > maxLines) {
                List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
                String last = cut.get(maxLines - 1);
                cut.set(maxLines - 1, (last.length() >= maxChars ? last.substring(0, maxChars - 1) : last) + "…");
                return cut;
            }
            return lines;
        }
    }
}
