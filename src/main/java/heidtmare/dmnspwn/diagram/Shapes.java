package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.diagram.Geometry.fmt;

import java.util.ArrayList;
import java.util.List;

import heidtmare.dmnspwn.diagram.DiagramView.Line;
import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.model.ElementKind;

/** SVG path data for the DMN notation shapes, plus label wrapping. */
final class Shapes {

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
