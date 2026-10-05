package heidtmare.dmnspwn.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import heidtmare.dmnspwn.model.BuiltInTypes;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;

/** Describes input data elements as form fields, with a placeholder and suggested values from their type. */
public final class InputForms {

    /** An input data element as a form field. */
    public record InputField(String id, String name, String typeRef, String placeholder, List<String> suggestions) {
    }

    private final Feel feel;
    private final Map<String, ItemDefinitionView> types = new HashMap<>();

    InputForms(List<ItemDefinitionView> itemDefinitions, Feel feel) {
        this.feel = feel;
        itemDefinitions.forEach(t -> types.putIfAbsent(t.name(), t));
    }

    InputField field(ElementView input) {
        return new InputField(input.id(), input.name(), input.typeRef(), placeholder(input.typeRef()),
                suggestions(input.typeRef()));
    }

    /**
     * The item definitions a type reference resolves through, in order, until it reaches a name that is not an item
     * definition (a built-in or unknown type). A cyclic definition ends the chain where it would repeat.
     */
    private List<ItemDefinitionView> typeChain(String typeRef) {
        List<ItemDefinitionView> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String t = typeRef; t != null && seen.add(t) && types.containsKey(t); t = types.get(t).typeRef()) {
            chain.add(types.get(t));
        }
        return chain;
    }

    private String placeholder(String typeRef) {
        List<ItemDefinitionView> chain = typeChain(typeRef);
        String base = chain.isEmpty() ? typeRef : chain.getLast().typeRef();
        for (ItemDefinitionView item : chain) {
            if (item.collection()) {
                base = "list";
                break;
            }
            if (!item.components().isEmpty()) {
                return structurePlaceholder(item);
            }
        }
        if (base == null) {
            return "FEEL expression";
        }
        return switch (BuiltInTypes.canonical(base)) {
            case "number" -> "e.g. 42";
            case "string" -> "e.g. \"text\"";
            case "boolean" -> "true or false";
            case "date" -> "e.g. date(\"2024-01-31\")";
            case "time" -> "e.g. time(\"10:30:00\")";
            case "date and time" -> "e.g. date and time(\"2024-01-31T10:30:00\")";
            case "days and time duration" -> "e.g. duration(\"P1DT2H\")";
            case "years and months duration" -> "e.g. duration(\"P1Y6M\")";
            case "list" -> "e.g. [1, 2, 3]";
            case "context" -> "e.g. {name: \"value\"}";
            default -> "FEEL expression";
        };
    }

    private static String structurePlaceholder(ItemDefinitionView item) {
        StringBuilder sb = new StringBuilder("{");
        for (ItemDefinitionView c : item.components()) {
            sb.append(sb.length() == 1 ? "" : ", ")
                    .append(Feel.isIdentifier(c.name()) ? c.name() : "\"" + c.name() + "\"").append(": …");
        }
        return sb.append('}').toString();
    }

    /** Allowed values of the (item definition) type, as FEEL literals. */
    private List<String> suggestions(String typeRef) {
        List<ItemDefinitionView> chain = typeChain(typeRef);
        for (ItemDefinitionView item : chain) {
            if (item.allowedValues() != null && !item.allowedValues().isBlank()) {
                Feel.Result r = feel.evaluate("[" + item.allowedValues() + "]", Scope.empty());
                return r.problem() != null ? List.of()
                        : Values.items(r.value()).stream().map(Values::format).toList();
            }
        }
        String base = chain.isEmpty() ? typeRef : chain.getLast().typeRef();
        return "boolean".equals(base) ? List.of("true", "false") : List.of();
    }
}
