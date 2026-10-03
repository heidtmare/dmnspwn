package heidtmare.dmnspwn.diagram;

import java.util.Locale;

/** Small geometry types shared by layout and rendering. */
public final class Geometry {

    private Geometry() {
    }

    public record Point(double x, double y) {
    }

    public record Bounds(double x, double y, double width, double height) {

        public double cx() {
            return x + width / 2;
        }

        public double cy() {
            return y + height / 2;
        }

        public double right() {
            return x + width;
        }

        public double bottom() {
            return y + height;
        }

        public Point center() {
            return new Point(cx(), cy());
        }

        public Bounds moveTo(double nx, double ny) {
            return new Bounds(nx, ny, width, height);
        }

        public boolean contains(Bounds other) {
            return other.x >= x && other.y >= y && other.right() <= right() && other.bottom() <= bottom();
        }

        /** Point where the segment from the centre towards {@code p} leaves this rectangle. */
        public Point clip(Point p) {
            double dx = p.x() - cx();
            double dy = p.y() - cy();
            if (dx == 0 && dy == 0) {
                return center();
            }
            double sx = dx == 0 ? Double.MAX_VALUE : (width / 2) / Math.abs(dx);
            double sy = dy == 0 ? Double.MAX_VALUE : (height / 2) / Math.abs(dy);
            double s = Math.min(Math.min(sx, sy), 1);
            return new Point(round(cx() + dx * s), round(cy() + dy * s));
        }
    }

    /** Straight connector between two shapes, clipped to their borders. */
    public static Point[] connect(Bounds source, Bounds target) {
        return new Point[] {source.clip(target.center()), target.clip(source.center())};
    }

    public static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Compact number formatting for SVG attributes. */
    public static String fmt(double v) {
        double r = round(v);
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }
}
