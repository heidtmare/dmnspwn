package heidtmare.dmnspwn.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Messages and decision table hits collected while evaluating one decision (or one ad-hoc expression). */
public final class Trace {

    public record Message(boolean error, String text) {
    }

    private final List<Message> messages = new ArrayList<>();
    private final Map<String, List<Integer>> matchedRules = new LinkedHashMap<>();

    public void error(String text) {
        messages.add(new Message(true, text));
    }

    public void warning(String text) {
        messages.add(new Message(false, text));
    }

    /** Records the 1-based rules of a decision table that matched, keyed by the table's id (or "" without one). */
    void matched(String tableId, List<Integer> rules) {
        matchedRules.put(tableId == null ? "" : tableId, List.copyOf(rules));
    }

    public List<Message> messages() {
        return messages;
    }

    public boolean hasErrors() {
        return messages.stream().anyMatch(Message::error);
    }

    public Map<String, List<Integer>> matchedRules() {
        return matchedRules;
    }
}
