package heidtmare.dmnspwn.diagram;

import static heidtmare.dmnspwn.diagram.Geometry.fmt;

import java.util.Locale;
import java.util.Map;

import heidtmare.dmnspwn.diagram.DiagramView.Edge;
import heidtmare.dmnspwn.diagram.DiagramView.Node;
import heidtmare.dmnspwn.model.ElementKind;

/**
 * The outcome of an evaluation drawn on top of a DRD: a status and a value badge per evaluated element (keyed by
 * element id). Decisions and input data without a mark were not evaluated; requirements between two marked
 * elements were used by the run.
 */
public record Overlay(Map<String, Mark> marks) {

    private static final int MAX_BADGE = 24;
    private static final double CHAR_WIDTH = 6.3;
    private static final double BADGE_HEIGHT = 16;
    private static final double BADGE_INSET = 5;

    public enum Status {
        OK, WARNING, ERROR, MISMATCH;

        String slug() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** @param detail extra text for the tooltip, such as the expected value of a failing test; may be null */
    public record Mark(Status status, String value, String detail) {
    }

    /**
     * A value badge inside the bottom of a shape (requirement edges end on the outline, so they do not cross it);
     * {@code labelShift} moves the name up to make room.
     */
    public record Badge(String x, String y, String width, String height, String textX, String textY, String text,
                        String title, String labelShift) {
    }

    public Mark mark(String elementId) {
        return marks.get(elementId);
    }

    /** Extra CSS classes for a node: its status, or {@code ev-skipped} for decisions and inputs not evaluated. */
    public String nodeClass(Node n) {
        Mark m = marks.get(n.elementId());
        if (m != null) {
            return "ev ev-" + m.status().slug();
        }
        return n.kind() == ElementKind.DECISION || n.kind() == ElementKind.INPUT_DATA ? "ev-skipped" : "";
    }

    /** Where a node links to on the evaluation page: its result row or input field, or null for the element page. */
    public String href(Node n) {
        if (!marks.containsKey(n.elementId())) {
            return null;
        }
        return (n.kind() == ElementKind.INPUT_DATA ? "#in-" : "#result-") + n.elementId();
    }

    public boolean active(Edge e) {
        return marks.containsKey(e.sourceId()) && marks.containsKey(e.targetId());
    }

    public Badge badge(Node n) {
        Mark m = marks.get(n.elementId());
        if (m == null) {
            return null;
        }
        String value = m.value() == null ? "" : m.value().strip().replaceAll("\\s+", " ");
        String text = value.length() > MAX_BADGE ? value.substring(0, MAX_BADGE - 1) + "…" : value;
        double w = Math.min(Math.max(text.length() * CHAR_WIDTH + 14, 28), n.bounds().width() - 2 * BADGE_INSET);
        double cx = n.bounds().x() + n.bounds().width() / 2;
        double y = n.bounds().y() + n.bounds().height() - BADGE_HEIGHT - BADGE_INSET;
        String title = n.name() + " = " + value + (m.detail() == null ? "" : "\n" + m.detail());
        return new Badge(fmt(cx - w / 2), fmt(y), fmt(w), fmt(BADGE_HEIGHT), fmt(cx), fmt(y + 11.5), text, title,
                "translate(0 " + fmt(-(BADGE_HEIGHT + BADGE_INSET) / 2) + ")");
    }
}
