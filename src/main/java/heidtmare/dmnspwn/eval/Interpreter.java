package heidtmare.dmnspwn.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValContext;
import org.camunda.feel.syntaxtree.ValFunction;

import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.ExpressionView.DecisionTable;
import heidtmare.dmnspwn.model.ExpressionView.OutputClause;
import heidtmare.dmnspwn.model.ExpressionView.Rule;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Evaluates boxed expressions (DMN chapter 7/8) against a {@link Scope}. FEEL text inside them is handed to
 * {@link Feel}; the boxes themselves (decision tables, contexts, invocations, …) are interpreted here.
 *
 * <p>Following DMN semantics, a failing expression yields {@code null} and is reported to the current
 * {@link Trace} instead of aborting the whole evaluation.
 */
final class Interpreter {

    private final Feel feel;
    private final Supplier<Trace> trace;

    Interpreter(Feel feel, Supplier<Trace> trace) {
        this.feel = feel;
        this.trace = trace;
    }

    Val evaluate(ExpressionView x, Scope scope) {
        if (x == null) {
            trace.get().warning("Missing expression evaluates to null");
            return Values.NULL;
        }
        return switch (x) {
            case ExpressionView.Literal l -> literal(l, scope);
            case DecisionTable t -> decisionTable(t, scope);
            case ExpressionView.Context c -> context(c, scope);
            case ExpressionView.Relation r -> relation(r, scope);
            case ExpressionView.ListExpr l -> Values.list(l.items().stream().map(i -> evaluate(i, scope)).toList());
            case ExpressionView.Invocation i -> invocation(i, scope);
            case ExpressionView.Function f -> function(f.kind(), f.parameters().stream()
                    .map(ExpressionView.Parameter::name).toList(), f.body(), scope);
            case ExpressionView.Conditional c -> Values.isTrue(evaluate(c.condition(), scope))
                    ? evaluate(c.then(), scope) : evaluate(c.otherwise(), scope);
            case ExpressionView.Iterator i -> iterator(i, scope);
            case ExpressionView.Filter f -> filter(f, scope);
            case ExpressionView.Unknown u -> {
                trace.get().error("Expression type '" + u.localName() + "' is not supported");
                yield Values.NULL;
            }
        };
    }

    // ---- FEEL text -----------------------------------------------------------------------------

    private Val literal(ExpressionView.Literal l, Scope scope) {
        String language = l.language();
        if (language != null && !language.isBlank() && !language.toLowerCase().contains("feel")) {
            trace.get().error("Expression language '" + language + "' is not supported");
            return Values.NULL;
        }
        return feel(l.text(), scope, null);
    }

    /** Evaluates FEEL text; blank text is {@code null}. {@code where} prefixes messages when given. */
    Val feel(String text, Scope scope, String where) {
        if (text == null || text.isBlank()) {
            return Values.NULL;
        }
        return report(feel.evaluate(text, scope), where);
    }

    private Val report(Feel.Result result, String where) {
        String prefix = where == null ? "" : where + ": ";
        if (result.failed()) {
            trace.get().error(prefix + result.error());
        }
        for (String w : result.warnings()) {
            trace.get().warning(prefix + w);
        }
        return result.value();
    }

    // ---- decision tables -----------------------------------------------------------------------

    private record Hit(int rule, Val value, List<Val> outputs) {
    }

    private Val decisionTable(DecisionTable t, Scope scope) {
        List<Val> inputs = new ArrayList<>();
        for (int i = 0; i < t.inputs().size(); i++) {
            inputs.add(feel(t.inputs().get(i).expression(), scope, "Input " + (i + 1)));
        }

        List<Hit> hits = new ArrayList<>();
        for (int r = 0; r < t.rules().size(); r++) {
            Rule rule = t.rules().get(r);
            if (matches(rule, inputs, scope, r + 1)) {
                List<Val> outputs = new ArrayList<>();
                for (int o = 0; o < t.outputs().size(); o++) {
                    String entry = o < rule.outputs().size() ? rule.outputs().get(o) : "";
                    outputs.add(feel(entry, scope, "Rule " + (r + 1) + ", output " + (o + 1)));
                }
                hits.add(new Hit(r + 1, combine(t, outputs), outputs));
            }
        }
        trace.get().matched(t.id(), hits.stream().map(Hit::rule).toList());

        if (hits.isEmpty()) {
            return defaults(t, scope);
        }
        String policy = t.hitPolicy() == null || t.hitPolicy().isBlank() ? "UNIQUE" : t.hitPolicy();
        return switch (policy) {
            case "FIRST" -> hits.getFirst().value();
            case "ANY" -> {
                Val first = hits.getFirst().value();
                if (hits.stream().allMatch(h -> Values.same(h.value(), first))) {
                    yield first;
                }
                trace.get().error("Hit policy ANY: matched rules " + rules(hits) + " have different outputs");
                yield Values.NULL;
            }
            case "PRIORITY" -> byPriority(t, hits, scope).getFirst().value();
            case "OUTPUT ORDER" -> Values.list(byPriority(t, hits, scope).stream().map(Hit::value).toList());
            case "RULE ORDER" -> Values.list(hits.stream().map(Hit::value).toList());
            case "COLLECT" -> collect(t, hits, scope);
            default -> {
                if (hits.size() > 1) {
                    trace.get().error("Hit policy UNIQUE: rules " + rules(hits) + " all match");
                    yield Values.NULL;
                }
                yield hits.getFirst().value();
            }
        };
    }

    private boolean matches(Rule rule, List<Val> inputs, Scope scope, int number) {
        for (int i = 0; i < inputs.size(); i++) {
            String entry = i < rule.inputs().size() ? rule.inputs().get(i) : "-";
            if (entry == null || entry.isBlank() || entry.strip().equals("-")) {
                continue;
            }
            Val result = report(feel.test(entry, inputs.get(i), scope), "Rule " + number + ", input " + (i + 1));
            if (!Values.isTrue(result)) {
                return false;
            }
        }
        return true;
    }

    /** One output is the value itself; several form a context keyed by output name. */
    private static Val combine(DecisionTable t, List<Val> outputs) {
        if (outputs.size() == 1) {
            return outputs.getFirst();
        }
        Map<String, Val> entries = new LinkedHashMap<>();
        for (int o = 0; o < outputs.size(); o++) {
            String name = t.outputs().get(o).name();
            entries.put(name == null || name.isBlank() ? "output " + (o + 1) : name, outputs.get(o));
        }
        return Values.context(entries);
    }

    private Val defaults(DecisionTable t, Scope scope) {
        if (t.outputs().stream().allMatch(o -> o.defaultOutput() == null || o.defaultOutput().isBlank())) {
            return Values.NULL;
        }
        List<Val> outputs = new ArrayList<>();
        for (int o = 0; o < t.outputs().size(); o++) {
            outputs.add(feel(t.outputs().get(o).defaultOutput(), scope, "Default output " + (o + 1)));
        }
        return combine(t, outputs);
    }

    /** Orders hits by the position of their outputs in each output clause's output values list. */
    private List<Hit> byPriority(DecisionTable t, List<Hit> hits, Scope scope) {
        List<List<Val>> priorities = new ArrayList<>();
        for (int o = 0; o < t.outputs().size(); o++) {
            OutputClause clause = t.outputs().get(o);
            String values = clause.outputValues();
            if (values == null || values.isBlank()) {
                priorities.add(List.of());
            } else {
                priorities.add(Values.items(feel("[" + values + "]", scope, "Output values " + (o + 1))));
            }
        }
        if (priorities.stream().allMatch(List::isEmpty)) {
            trace.get().warning("Hit policy " + t.hitPolicy() + " needs output values to rank outputs; using rule order");
        }
        Comparator<Hit> order = (a, b) -> 0;
        for (int o = 0; o < priorities.size(); o++) {
            List<Val> list = priorities.get(o);
            int index = o;
            order = order.thenComparingInt(h -> rank(list, h.outputs().get(index)));
        }
        return hits.stream().sorted(order).toList();
    }

    private static int rank(List<Val> priorities, Val value) {
        for (int i = 0; i < priorities.size(); i++) {
            if (Values.same(priorities.get(i), value)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Val collect(DecisionTable t, List<Hit> hits, Scope scope) {
        List<Val> values = hits.stream().map(Hit::value).toList();
        String aggregation = t.aggregation();
        if (aggregation == null || aggregation.isBlank()) {
            return Values.list(values);
        }
        if (t.outputs().size() > 1) {
            trace.get().error("Aggregation " + aggregation + " requires a single output");
            return Values.NULL;
        }
        if ("COUNT".equals(aggregation)) {
            // DMN: the number of distinct outputs.
            List<Val> distinct = new ArrayList<>();
            for (Val v : values) {
                if (distinct.stream().noneMatch(d -> Values.same(d, v))) {
                    distinct.add(v);
                }
            }
            return Values.number(distinct.size());
        }
        String function = switch (aggregation) {
            case "SUM" -> "sum";
            case "MIN" -> "min";
            case "MAX" -> "max";
            default -> null;
        };
        if (function == null) {
            trace.get().error("Unknown aggregation '" + aggregation + "'");
            return Values.NULL;
        }
        return feel(function + "(outputs)", scope.child().put("outputs", Values.list(values)), "Aggregation " + aggregation);
    }

    private static String rules(List<Hit> hits) {
        return String.join(", ", hits.stream().map(h -> String.valueOf(h.rule())).toList());
    }

    // ---- other boxes ---------------------------------------------------------------------------

    private Val context(ExpressionView.Context c, Scope scope) {
        Scope inner = scope.child();
        Map<String, Val> entries = new LinkedHashMap<>();
        for (ExpressionView.ContextEntry entry : c.entries()) {
            Val value = evaluate(entry.value(), inner);
            if (entry.name() == null) {
                return value;
            }
            inner.put(entry.name(), value);
            entries.put(entry.name(), value);
        }
        return Values.context(entries);
    }

    private Val relation(ExpressionView.Relation r, Scope scope) {
        List<Val> rows = new ArrayList<>();
        for (List<ExpressionView> row : r.rows()) {
            Map<String, Val> entries = new LinkedHashMap<>();
            for (int i = 0; i < r.columns().size(); i++) {
                entries.put(r.columns().get(i).name(), i < row.size() ? evaluate(row.get(i), scope) : Values.NULL);
            }
            rows.add(Values.context(entries));
        }
        return Values.list(rows);
    }

    private Val invocation(ExpressionView.Invocation i, Scope scope) {
        if (i.function() == null || i.function().isBlank() || "…".equals(i.function())) {
            trace.get().error("Invocation without a function name");
            return Values.NULL;
        }
        Val callee = feel(i.function(), scope, "Invoked function");
        if (!(callee instanceof ValFunction function)) {
            trace.get().error("'" + i.function() + "' is not a function");
            return Values.NULL;
        }
        Map<String, Val> args = new LinkedHashMap<>();
        for (ExpressionView.Binding b : i.bindings()) {
            args.put(b.name(), b.value() == null ? Values.NULL : evaluate(b.value(), scope));
        }
        return invoke(i.function(), function, args);
    }

    /** Calls a FEEL function with named arguments; parameters without an argument are {@code null}. */
    Val invoke(String name, ValFunction function, Map<String, Val> args) {
        List<String> params = CollectionConverters.asJava(function.params());
        for (String arg : args.keySet()) {
            if (!params.contains(arg)) {
                trace.get().error("Function '" + name + "' has no parameter '" + arg + "'");
                return Values.NULL;
            }
        }
        List<Val> values = params.stream().map(p -> args.getOrDefault(p, Values.NULL)).toList();
        try {
            Object result = function.invoke().apply(Values.scalaList(values));
            return result instanceof Val v ? v : Feel.VAL_MAPPER.toVal(result);
        } catch (RuntimeException e) {
            trace.get().error("Invoking '" + name + "' failed: " + e.getMessage());
            return Values.NULL;
        }
    }

    /** A FEEL function whose body is a boxed expression, closing over {@code scope}. */
    ValFunction function(String kind, List<String> params, ExpressionView body, Scope scope) {
        boolean supported = kind == null || kind.isBlank() || "FEEL".equalsIgnoreCase(kind);
        return new ValFunction(Values.scalaList(params), args -> {
            if (!supported) {
                trace.get().error(kind + " functions are not supported");
                return Values.NULL;
            }
            Scope call = scope.child();
            List<Val> values = CollectionConverters.asJava(args);
            for (int p = 0; p < params.size(); p++) {
                call.put(params.get(p), p < values.size() ? values.get(p) : Values.NULL);
            }
            return evaluate(body, call);
        }, false);
    }

    private Val iterator(ExpressionView.Iterator it, Scope scope) {
        Val collection = evaluate(it.collection(), scope);
        if (Values.isNull(collection)) {
            return Values.NULL;
        }
        Function<Val, Val> body = item -> evaluate(it.body(), scope.child().put(it.variable(), item));
        List<Val> items = Values.items(collection);
        return switch (it.keyword()) {
            case "some" -> Values.bool(items.stream().anyMatch(item -> Values.isTrue(body.apply(item))));
            case "every" -> Values.bool(items.stream().allMatch(item -> Values.isTrue(body.apply(item))));
            default -> Values.list(items.stream().map(body).toList());
        };
    }

    private Val filter(ExpressionView.Filter f, Scope scope) {
        Val collection = evaluate(f.collection(), scope);
        if (Values.isNull(collection)) {
            return Values.NULL;
        }
        List<Val> kept = new ArrayList<>();
        for (Val item : Values.items(collection)) {
            Scope inner = scope.child();
            if (item instanceof ValContext c) {
                Values.entries(c).forEach(inner::put);
            }
            inner.put("item", item);
            if (Values.isTrue(evaluate(f.match(), inner))) {
                kept.add(item);
            }
        }
        return Values.list(kept);
    }
}
