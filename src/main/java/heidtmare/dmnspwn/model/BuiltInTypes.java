package heidtmare.dmnspwn.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The FEEL built-in type names a {@code typeRef} may use without an item definition. */
public final class BuiltInTypes {

    /** The types offered when choosing a type reference, in display order. */
    public static final List<String> OFFERED = List.of("string", "number", "boolean", "date", "time",
            "date and time", "days and time duration", "years and months duration", "Any", "context", "list");

    /** XML Schema style names some tools write, by the FEEL name they stand for. */
    private static final Map<String, String> ALIASES = Map.of(
            "dateTime", "date and time",
            "dayTimeDuration", "days and time duration",
            "yearMonthDuration", "years and months duration");

    /** Valid type names that are not offered, as they are rarely what a model element should declare. */
    private static final Set<String> OTHERS = Set.of("function", "range", "null");

    private BuiltInTypes() {
    }

    public static boolean isBuiltIn(String name) {
        return OFFERED.contains(name) || ALIASES.containsKey(name) || OTHERS.contains(name);
    }

    /** The FEEL name of a built-in type, resolving aliases; other names are returned unchanged. */
    public static String canonical(String name) {
        return name == null ? null : ALIASES.getOrDefault(name, name);
    }
}
