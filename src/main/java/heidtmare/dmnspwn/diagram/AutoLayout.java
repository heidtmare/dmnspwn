package heidtmare.dmnspwn.diagram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;

import heidtmare.dmnspwn.diagram.Geometry.Bounds;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.Views.ConnectionView;

/**
 * Layered layout for models without DMNDI: required elements sit below the elements
 * that require them, so information flows upwards as is conventional for DRDs.
 */
public final class AutoLayout {

    private static final double H_GAP = 50;
    private static final double V_GAP = 70;
    private static final double ORIGIN = 40;

    private AutoLayout() {
    }

    public static Map<String, Bounds> layout(DmnReader reader) {
        Map<String, Element> nodes = reader.nodeElements();
        List<String> drg = new ArrayList<>();
        List<String> annotations = new ArrayList<>();
        nodes.forEach((id, e) -> (DmnReader.kindOf(e) == ElementKind.TEXT_ANNOTATION ? annotations : drg).add(id));

        Map<String, Set<String>> preds = new HashMap<>();
        Map<String, Set<String>> succs = new HashMap<>();
        for (ConnectionView c : reader.connections()) {
            if (c.kind() != ConnectionKind.ASSOCIATION && c.sourceLocal() && nodes.containsKey(c.targetId())) {
                preds.computeIfAbsent(c.targetId(), k -> new HashSet<>()).add(c.sourceId());
                succs.computeIfAbsent(c.sourceId(), k -> new HashSet<>()).add(c.targetId());
            }
        }

        Map<String, Integer> layer = new HashMap<>();
        for (String id : drg) {
            layerOf(id, preds, layer, new HashSet<>());
        }
        // Pull sources (e.g. input data) up to just below their lowest consumer to shorten edges.
        for (String id : drg) {
            if (preds.getOrDefault(id, Set.of()).isEmpty()) {
                int min = succs.getOrDefault(id, Set.of()).stream().filter(layer::containsKey)
                        .mapToInt(layer::get).min().orElse(1);
                layer.put(id, Math.max(0, min - 1));
            }
        }
        int maxLayer = layer.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i <= maxLayer; i++) {
            rows.add(new ArrayList<>());
        }
        drg.forEach(id -> rows.get(layer.get(id)).add(id));

        // Barycentre ordering sweeps to reduce crossings.
        for (int pass = 0; pass < 4; pass++) {
            for (int l = 1; l <= maxLayer; l++) {
                order(rows.get(l), rows.get(l - 1), preds);
            }
            for (int l = maxLayer - 1; l >= 0; l--) {
                order(rows.get(l), rows.get(l + 1), succs);
            }
        }

        Map<String, Bounds> result = new LinkedHashMap<>();
        double rowWidthMax = 0;
        Map<Integer, Double> rowWidths = new HashMap<>();
        for (int l = 0; l <= maxLayer; l++) {
            double w = 0;
            for (String id : rows.get(l)) {
                w += size(nodes.get(id))[0] + H_GAP;
            }
            rowWidths.put(l, Math.max(0, w - H_GAP));
            rowWidthMax = Math.max(rowWidthMax, w - H_GAP);
        }
        double y = ORIGIN;
        for (int l = maxLayer; l >= 0; l--) {
            double rowHeight = 0;
            double x = ORIGIN + (rowWidthMax - rowWidths.get(l)) / 2;
            for (String id : rows.get(l)) {
                double[] size = size(nodes.get(id));
                rowHeight = Math.max(rowHeight, size[1]);
                result.put(id, new Bounds(x, y, size[0], size[1]));
                x += size[0] + H_GAP;
            }
            if (!rows.get(l).isEmpty()) {
                y += rowHeight + V_GAP;
            }
        }

        // Annotations go in a column to the right, next to the element they annotate.
        double column = ORIGIN + rowWidthMax + H_GAP * 2;
        double freeY = ORIGIN;
        for (String id : annotations) {
            double[] size = size(nodes.get(id));
            double ay = freeY;
            for (ConnectionView c : reader.connections()) {
                if (c.kind() == ConnectionKind.ASSOCIATION) {
                    String other = id.equals(c.sourceId()) ? c.targetId() : id.equals(c.targetId()) ? c.sourceId() : null;
                    if (other != null && result.containsKey(other)) {
                        ay = Math.max(freeY, result.get(other).y());
                        break;
                    }
                }
            }
            result.put(id, new Bounds(column, ay, size[0], size[1]));
            freeY = ay + size[1] + 20;
        }
        return result;
    }

    private static double[] size(Element e) {
        ElementKind kind = DmnReader.kindOf(e);
        return kind == ElementKind.DECISION_SERVICE ? new double[] {220, 110} : new double[] {kind.width(), kind.height()};
    }

    private static int layerOf(String id, Map<String, Set<String>> preds, Map<String, Integer> layer,
                               Set<String> visiting) {
        Integer known = layer.get(id);
        if (known != null) {
            return known;
        }
        visiting.add(id);
        int l = 0;
        for (String p : preds.getOrDefault(id, Set.of())) {
            if (!visiting.contains(p)) { // ignore back edges of (invalid) cycles
                l = Math.max(l, layerOf(p, preds, layer, visiting) + 1);
            }
        }
        visiting.remove(id);
        layer.put(id, l);
        return l;
    }

    private static void order(List<String> row, List<String> reference, Map<String, Set<String>> neighbours) {
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < reference.size(); i++) {
            pos.put(reference.get(i), i);
        }
        Map<String, Double> bary = new HashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String id = row.get(i);
            double sum = 0;
            int n = 0;
            for (String nb : neighbours.getOrDefault(id, Set.of())) {
                Integer p = pos.get(nb);
                if (p != null) {
                    sum += p;
                    n++;
                }
            }
            bary.put(id, n == 0 ? i : sum / n);
        }
        row.sort(Comparator.comparingDouble(bary::get));
    }
}
