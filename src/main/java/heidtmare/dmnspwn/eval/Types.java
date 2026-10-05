package heidtmare.dmnspwn.eval;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValContext;
import org.camunda.feel.syntaxtree.ValDate;
import org.camunda.feel.syntaxtree.ValDateTime;
import org.camunda.feel.syntaxtree.ValList;

import heidtmare.dmnspwn.model.BuiltInTypes;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;

/**
 * Checks values against type references (DMN 1.5 §7.3.2 type conformance, §10.3.2.9.4 implicit conversions).
 * Built-in types and the model's item definitions are checked, including allowed values, type constraints,
 * collections and structures; a type reference that resolves to neither (e.g. an imported type) is not checked.
 * {@code null} conforms to every type.
 */
public final class Types {

    /** A value after conversion, or {@code null} with the {@code problem} that it does not conform. */
    public record Conformed(Val value, String problem) {
        static Conformed ok(Val value) {
            return new Conformed(value, null);
        }

        static Conformed failure(String problem) {
            return new Conformed(Values.NULL, problem);
        }

        public boolean failed() {
            return problem != null;
        }
    }

    private static final int SHOWN_LENGTH = 60;

    private final Feel feel;
    private final Map<String, ItemDefinitionView> items = new HashMap<>();

    public Types(List<ItemDefinitionView> itemDefinitions, Feel feel) {
        this.feel = feel;
        itemDefinitions.forEach(t -> items.putIfAbsent(t.name(), t));
    }

    /**
     * The item definitions a type reference resolves through, in order, until it reaches a name that is not an item
     * definition (a built-in or unknown type). A cyclic definition ends the chain where it would repeat.
     */
    List<ItemDefinitionView> chain(String typeRef) {
        List<ItemDefinitionView> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String t = typeRef; t != null && seen.add(t) && items.containsKey(t); t = items.get(t).typeRef()) {
            chain.add(items.get(t));
        }
        return chain;
    }

    /** {@code value} converted to {@code typeRef}, or a failure when it does not conform. */
    public Conformed conform(Val value, String typeRef) {
        return conform(value, typeRef, new HashSet<>());
    }

    /**
     * Whether {@code value} satisfies {@code tests} (allowed values, input or output values written as unary
     * tests); null when it does, else the problem. {@code null} values and blank tests are not checked.
     */
    public String allowed(Val value, String tests, Scope scope, String what) {
        if (Values.isNull(value) || tests == null || tests.isBlank()) {
            return null;
        }
        Feel.Result r = feel.test(tests, value, scope);
        if (r.failed()) {
            return what + " (" + tests.strip() + ") could not be evaluated: " + r.error();
        }
        return Values.isTrue(r.value()) ? null : shown(value) + " is not in " + what + " (" + tests.strip() + ")";
    }

    private Conformed conform(Val value, String typeRef, Set<String> seen) {
        if (Values.isNull(value) || typeRef == null || typeRef.isBlank()) {
            return Conformed.ok(value);
        }
        ItemDefinitionView item = items.get(typeRef);
        if (item == null) {
            return builtIn(value, BuiltInTypes.canonical(typeRef.strip()));
        }
        if (!seen.add(typeRef)) {
            return Conformed.ok(value);
        }
        Conformed c = item(value, item, typeRef, seen);
        seen.remove(typeRef);
        return c;
    }

    private Conformed item(Val value, ItemDefinitionView item, String name, Set<String> seen) {
        if (Values.isNull(value)) {
            return Conformed.ok(value);
        }
        if (item.collection()) {
            // to singleton list
            List<Val> elements = value instanceof ValList ? Values.items(value) : List.of(value);
            List<Val> converted = new ArrayList<>(elements.size());
            for (int i = 0; i < elements.size(); i++) {
                Conformed c = element(elements.get(i), item, name, seen);
                if (c.failed()) {
                    return Conformed.failure("item " + (i + 1) + " of " + name + ": " + c.problem());
                }
                converted.add(c.value());
            }
            return constrained(Values.list(converted), item, name);
        }
        Conformed c = element(value, item, name, seen);
        return c.failed() ? c : constrained(c.value(), item, name);
    }

    /** A single (non-collection) value of the item definition, with its allowed values. */
    private Conformed element(Val value, ItemDefinitionView item, String name, Set<String> seen) {
        if (Values.isNull(value)) {
            return Conformed.ok(value);
        }
        Conformed c;
        if (item.function()) {
            c = builtIn(value, "function");
        } else if (!item.components().isEmpty()) {
            c = structure(value, item, name, seen);
        } else {
            c = conform(value, item.typeRef(), seen);
        }
        if (c.failed()) {
            return c;
        }
        String problem = allowed(c.value(), item.allowedValues(), Scope.empty(), "the allowed values of " + name);
        return problem == null ? c : Conformed.failure(problem);
    }

    private Conformed structure(Val value, ItemDefinitionView item, String name, Set<String> seen) {
        Val single = fromSingletonList(value);
        if (!(single instanceof ValContext context)) {
            return mismatch(value, name);
        }
        Map<String, Val> entries = new LinkedHashMap<>(Values.entries(context));
        for (ItemDefinitionView component : item.components()) {
            Val entry = entries.getOrDefault(component.name(), Values.NULL);
            Conformed c = item(entry, component, component.name(), seen);
            if (c.failed()) {
                return Conformed.failure(name + "." + component.name() + ": " + c.problem());
            }
            if (entries.containsKey(component.name())) {
                entries.put(component.name(), c.value());
            }
        }
        return Conformed.ok(Values.context(entries));
    }

    private Conformed constrained(Val value, ItemDefinitionView item, String name) {
        String problem = allowed(value, item.typeConstraint(), Scope.empty(), "the type constraint of " + name);
        return problem == null ? Conformed.ok(value) : Conformed.failure(problem);
    }

    private static Conformed builtIn(Val value, String type) {
        if (!BuiltInTypes.isBuiltIn(type) || "Any".equals(type) || type.equals(Values.typeName(value))) {
            return Conformed.ok(value);
        }
        if (value instanceof ValList && !"list".equals(type)) {
            Val single = fromSingletonList(value);
            return single == value ? mismatch(value, type) : builtIn(single, type);
        }
        if (value instanceof ValDate d && "date and time".equals(type)) {
            return Conformed.ok(new ValDateTime(d.value().atStartOfDay(ZoneOffset.UTC)));
        }
        return mismatch(value, type);
    }

    /** The only item of a one-item list (DMN "from singleton list"); other values unchanged. */
    private static Val fromSingletonList(Val value) {
        if (value instanceof ValList) {
            List<Val> items = Values.items(value);
            return items.size() == 1 ? items.getFirst() : value;
        }
        return value;
    }

    private static Conformed mismatch(Val value, String type) {
        return Conformed.failure(shown(value) + " is not a " + type);
    }

    /** The value with its type, shortened, e.g. {@code string "42"}. */
    private static String shown(Val value) {
        String text = Values.format(value);
        if (text.length() > SHOWN_LENGTH) {
            text = text.substring(0, SHOWN_LENGTH - 1) + "…";
        }
        String type = Values.typeName(value);
        return type.isEmpty() ? text : type + " " + text;
    }
}
