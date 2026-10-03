package heidtmare.dmnspwn.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which local elements each element requires, following information, knowledge and authority requirements. */
public final class RequirementGraph {

    private final Map<String, List<String>> requires = new HashMap<>();

    public RequirementGraph(Collection<ConnectionElement> connections) {
        for (ConnectionElement c : connections) {
            if (c.kind().isRequirement() && c.sourceId() != null && c.targetId() != null) {
                requires.computeIfAbsent(c.targetId(), k -> new ArrayList<>()).add(c.sourceId());
            }
        }
    }

    /** Whether {@code a} is {@code b} or (transitively) requires it. */
    public boolean dependsOn(String a, String b) {
        Deque<String> queue = new ArrayDeque<>(List.of(a));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.pop();
            if (current.equals(b)) {
                return true;
            }
            if (seen.add(current)) {
                queue.addAll(requires.getOrDefault(current, List.of()));
            }
        }
        return false;
    }

    /** One element of each requirement cycle reachable from the given elements, in discovery order. */
    public Set<String> cycles(Collection<String> from) {
        Set<String> done = new HashSet<>();
        Set<String> found = new LinkedHashSet<>();
        for (String id : from) {
            visit(id, new HashSet<>(), done, found);
        }
        return found;
    }

    private void visit(String id, Set<String> path, Set<String> done, Set<String> found) {
        if (done.contains(id)) {
            return;
        }
        if (!path.add(id)) {
            found.add(id);
            return;
        }
        for (String next : requires.getOrDefault(id, List.of())) {
            visit(next, path, done, found);
        }
        path.remove(id);
        done.add(id);
    }
}
