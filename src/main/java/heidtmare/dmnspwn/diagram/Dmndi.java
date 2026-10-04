package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.xml.DmnDocument.anyChildren;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Reads geometry from DMNDI: shapes, their bounds and waypoints. */
public final class Dmndi {

    private Dmndi() {
    }

    /** A shape's bounds; empty when it has none or any coordinate is missing or not a finite number. */
    public static Optional<Bounds> bounds(Element shape) {
        return anyChildren(shape, "Bounds").stream().findFirst().flatMap(b -> {
            OptionalDouble x = num(b, "x");
            OptionalDouble y = num(b, "y");
            OptionalDouble w = num(b, "width");
            OptionalDouble h = num(b, "height");
            return x.isPresent() && y.isPresent() && w.isPresent() && h.isPresent()
                    ? Optional.of(new Bounds(x.getAsDouble(), y.getAsDouble(), w.getAsDouble(), h.getAsDouble()))
                    : Optional.empty();
        });
    }

    /** An edge's or divider line's points; empty when any coordinate is missing or not a finite number. */
    public static List<Point> waypoints(Element owner) {
        List<Point> points = new ArrayList<>();
        for (Element w : anyChildren(owner, "waypoint")) {
            OptionalDouble x = num(w, "x");
            OptionalDouble y = num(w, "y");
            if (x.isEmpty() || y.isEmpty()) {
                return List.of();
            }
            points.add(new Point(x.getAsDouble(), y.getAsDouble()));
        }
        return List.copyOf(points);
    }

    /** Whether a shape has a {@code Bounds} element that {@link #bounds} cannot read. */
    public static boolean hasInvalidBounds(Element shape) {
        return !anyChildren(shape, "Bounds").isEmpty() && bounds(shape).isEmpty();
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

    private static OptionalDouble num(Element e, String attr) {
        try {
            double v = Double.parseDouble(e.getAttribute(attr));
            return Double.isFinite(v) ? OptionalDouble.of(v) : OptionalDouble.empty();
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }

    /** The bounds of an element's shape on a diagram, if the diagram exists and shows the element. */
    public static Optional<Bounds> shapeBounds(DmnDocument doc, String diagramId, String elementId) {
        return doc.diagram(diagramId).map(d -> shapesByElement(doc, d).get(elementId)).flatMap(Dmndi::bounds);
    }
}
