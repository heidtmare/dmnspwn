package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.xml.DmnDocument.anyChildren;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.diagram.Geometry.Point;
import heidtmare.dmnspwn.xml.DmnDocument;

/** Reads geometry from DMNDI: shapes, their bounds and waypoints. */
public final class Dmndi {

    private Dmndi() {
    }

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

    private static double num(Element e, String attr) {
        try {
            return Double.parseDouble(e.getAttribute(attr));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
