package heidtmare.dmnspwn.eval;

import java.util.List;

import heidtmare.dmnspwn.model.BuiltInTypes;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;

/** Describes input data elements as form fields, with a placeholder and suggested values from their type. */
public final class InputForms {

    /** An input data element as a form field. */
    public record InputField(String id, String name, String typeRef, String placeholder, List<String> suggestions) {
    }

    private final Feel feel;
    private final Types types;

    InputForms(Types types, Feel feel) {
        this.feel = feel;
        this.types = types;
    }

    InputField field(ElementView input) {
        return new InputField(input.id(), input.name(), input.typeRef(), placeholder(input.typeRef()),
                suggestions(input.typeRef()));
    }

    private String placeholder(String typeRef) {
        List<ItemDefinitionView> chain = types.chain(typeRef);
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
        List<ItemDefinitionView> chain = types.chain(typeRef);
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
