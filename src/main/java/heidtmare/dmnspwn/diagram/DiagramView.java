package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.diagram.Geometry.fmt;

import java.util.List;

import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.ElementKind;

/**
 * A fully laid-out decision requirements diagram, ready for server-side SVG rendering.
 *
 * @param fromDmndi true when positions come from the model's DMNDI, false for automatic layout
 */
public record DiagramView(String diagramId, String name, Bounds viewport, List<Node> nodes, List<Edge> edges,
                          boolean fromDmndi) {

    public String viewBox() {
        return fmt(viewport.x()) + " " + fmt(viewport.y()) + " " + fmt(viewport.width()) + " "
                + fmt(viewport.height());
    }

    public String width() {
        return fmt(viewport.width());
    }

    public String height() {
        return fmt(viewport.height());
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** Containers first so nested shapes paint on top of them. */
    public List<Node> containers() {
        return nodes.stream().filter(n -> n.kind() == ElementKind.DECISION_SERVICE).toList();
    }

    public List<Node> shapes() {
        return nodes.stream().filter(n -> n.kind() != ElementKind.DECISION_SERVICE).toList();
    }

    public record Line(String text, String x, String y) {
    }

    /**
     * @param kind     null for shapes referencing imported elements
     * @param external true for imported elements (not linkable)
     */
    public record Node(String elementId, ElementKind kind, String name, Bounds bounds,
                       List<Line> lines, String textAnchor, String path, String dividerPath, boolean external) {

        public String cssClass() {
            return "node-" + (kind == null ? "external" : kind.slug()) + (external ? " node-external" : "");
        }

        public String x() {
            return fmt(bounds.x());
        }

        public String y() {
            return fmt(bounds.y());
        }

        public String w() {
            return fmt(bounds.width());
        }

        public String h() {
            return fmt(bounds.height());
        }

        public boolean annotation() {
            return kind == ElementKind.TEXT_ANNOTATION;
        }

        public String title() {
            return (kind == null ? "Imported element" : kind.displayName()) + ": " + name;
        }
    }

    public record Edge(String ref, ConnectionKind kind, String sourceId, String targetId, List<Point> points) {

        public String d() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < points.size(); i++) {
                sb.append(i == 0 ? "M" : " L").append(fmt(points.get(i).x())).append(' ')
                        .append(fmt(points.get(i).y()));
            }
            return sb.toString();
        }

        public String marker() {
            return switch (kind) {
                case INFORMATION -> "url(#m-information)";
                case KNOWLEDGE -> "url(#m-knowledge)";
                case AUTHORITY -> "url(#m-authority)";
                case ASSOCIATION -> "none";
            };
        }

        public String cssClass() {
            return "edge edge-" + kind.slug();
        }
    }

    public record DiagramRef(String id, String name) {
    }
}
