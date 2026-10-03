package heidtmare.dmnspwn.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValFunction;

import heidtmare.dmnspwn.eval.Evaluation.DecisionResult;
import heidtmare.dmnspwn.eval.Evaluation.ExpressionResult;
import heidtmare.dmnspwn.eval.Evaluation.InputResult;
import heidtmare.dmnspwn.model.BuiltInTypes;
import heidtmare.dmnspwn.model.ConnectionKind;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.model.ExpressionView;
import heidtmare.dmnspwn.model.Views.ConnectionView;
import heidtmare.dmnspwn.model.Views.ElementView;
import heidtmare.dmnspwn.model.Views.ItemDefinitionView;
import heidtmare.dmnspwn.model.Views.RefView;

import scala.jdk.javaapi.CollectionConverters;

/**
 * Evaluates the decisions of one model (DMN chapter 8 execution semantics). Each decision sees exactly the
 * elements it requires: input data and decisions through information requirements, business knowledge models
 * and decision services (as functions) through knowledge requirements. Imported elements are not resolved.
 */
public final class ModelEvaluator {

    /** An input data element as a form field. */
    public record InputField(String id, String name, String typeRef, String placeholder, List<String> suggestions) {
    }

    private final Feel feel;
    private final Map<String, ElementView> elements = new LinkedHashMap<>();
    private final Map<String, ItemDefinitionView> types = new HashMap<>();
    private final Feel.QuotedNames quotedNames;
    private final List<ElementView> decisions;
    private List<InputField> inputFields;

    public ModelEvaluator(DmnReader reader, Feel feel) {
        this.feel = feel;
        reader.elements().forEach(e -> elements.put(e.id(), e));
        reader.itemDefinitions().forEach(t -> types.putIfAbsent(t.name(), t));
        this.quotedNames = Feel.QuotedNames.of(names(reader));
        this.decisions = elements.values().stream().filter(e -> e.kind() == ElementKind.DECISION).toList();
    }

    public List<ElementView> decisions() {
        return decisions;
    }

    public List<InputField> inputFields() {
        if (inputFields == null) {
            inputFields = elements.values().stream().filter(e -> e.kind() == ElementKind.INPUT_DATA)
                    .map(e -> new InputField(e.id(), e.name(), e.typeRef(), placeholder(e.typeRef()),
                            suggestions(e.typeRef())))
                    .toList();
        }
        return inputFields;
    }

    /**
     * Evaluates the given decisions (all decisions when empty) and an optional ad-hoc FEEL expression for
     * input data values written as FEEL expressions, keyed by input data id.
     */
    public Evaluation evaluate(Map<String, String> inputTexts, Collection<String> decisionIds, String expression) {
        Run run = new Run();
        List<InputResult> inputs = new ArrayList<>();
        for (InputField field : inputFields()) {
            String text = inputTexts.getOrDefault(field.id(), "");
            Val value = Values.NULL;
            String error = null;
            if (!text.isBlank()) {
                Feel.Result r = feel.evaluate(text, Scope.empty());
                if (r.failed() || !r.warnings().isEmpty()) {
                    error = r.failed() ? r.error() : String.join("; ", r.warnings());
                }
                value = r.value();
            }
            run.inputs.put(field.id(), value);
            inputs.add(new InputResult(field.id(), field.name(), text, value, error));
        }

        Set<String> requested = new LinkedHashSet<>(decisionIds);
        boolean hasExpression = expression != null && !expression.isBlank();
        if (requested.isEmpty() && !hasExpression) {
            decisions().forEach(d -> requested.add(d.id()));
        }
        for (String id : requested) {
            ElementView e = elements.get(id);
            if (e != null && e.kind() == ElementKind.DECISION) {
                run.decision(e);
            }
        }

        ExpressionResult adHoc = null;
        if (hasExpression) {
            Trace trace = new Trace();
            run.traces.push(trace);
            Val value = guarded(() -> run.interpreter.feel(expression, run.globalScope(), null), trace);
            run.traces.pop();
            adHoc = new ExpressionResult(expression, value, trace.messages());
        }

        List<DecisionResult> decisions = new ArrayList<>();
        for (Map.Entry<String, Trace> e : run.done.entrySet()) {
            ElementView d = elements.get(e.getKey());
            ExpressionView logic = d.logic();
            boolean table = logic instanceof ExpressionView.DecisionTable;
            List<Integer> rules = table
                    ? e.getValue().matchedRules().getOrDefault(Optional.ofNullable(logic.id()).orElse(""), List.of())
                    : List.of();
            decisions.add(new DecisionResult(d.id(), d.name(), d.typeRef(), run.values.get(d.id()),
                    e.getValue().messages(), rules, table, requested.contains(d.id())));
        }
        return new Evaluation(inputs, decisions, adHoc);
    }

    private static Val guarded(Supplier<Val> body, Trace trace) {
        try {
            return body.get();
        } catch (StackOverflowError e) {
            trace.error("Evaluation recursed too deeply");
        } catch (RuntimeException e) {
            trace.error("Evaluation failed: " + e.getMessage());
        }
        return Values.NULL;
    }

    /** State of one evaluation: supplied inputs, evaluated decisions and the trace being written. */
    private final class Run {

        final Map<String, Val> inputs = new HashMap<>();
        /** Decision values fixed from outside, i.e. the input decisions of an invoked decision service. */
        final Map<String, Val> overrides = new HashMap<>();
        final Map<String, Val> values = new HashMap<>();
        /** Evaluated decisions with their traces, in evaluation (dependency) order. */
        final Map<String, Trace> done = new LinkedHashMap<>();
        final Set<String> evaluating = new HashSet<>();
        final Map<String, ValFunction> functions = new HashMap<>();
        final Deque<Trace> traces = new ArrayDeque<>();
        final Interpreter interpreter = new Interpreter(feel, this::trace);

        Run() {
            traces.push(new Trace());
        }

        Trace trace() {
            return traces.peek();
        }

        Val decision(ElementView d) {
            if (overrides.containsKey(d.id())) {
                return overrides.get(d.id());
            }
            if (values.containsKey(d.id())) {
                return values.get(d.id());
            }
            if (!evaluating.add(d.id())) {
                trace().error("Decision '" + d.name() + "' depends on itself");
                return Values.NULL;
            }
            Trace trace = new Trace();
            traces.push(trace);
            Val value;
            if (d.logic() == null) {
                trace.warning("No decision logic; the result is null");
                value = Values.NULL;
            } else {
                Scope scope = requirements(d);
                value = guarded(() -> interpreter.evaluate(d.logic(), scope), trace);
            }
            traces.pop();
            evaluating.remove(d.id());
            values.put(d.id(), value);
            done.put(d.id(), trace);
            return value;
        }

        /** The scope of a decision or BKM: one variable per required element, named like the element. */
        Scope requirements(ElementView e) {
            Scope scope = Scope.root(quotedNames);
            for (ConnectionView c : e.requires()) {
                if (c.kind() == ConnectionKind.AUTHORITY) {
                    continue;
                }
                ElementView source = c.sourceLocal() ? elements.get(c.sourceId()) : null;
                if (source == null) {
                    trace().warning("Required element '" + c.sourceName() + "' is imported or missing and was not evaluated");
                    continue;
                }
                Val value = value(source);
                if (value != null) {
                    scope.put(source.name(), value);
                }
            }
            return scope;
        }

        ValFunction knowledgeModel(ElementView bkm) {
            ValFunction f = functions.get(bkm.id());
            if (f == null) {
                if (!evaluating.add(bkm.id())) {
                    trace().error("Business knowledge model '" + bkm.name() + "' depends on itself");
                    return interpreter.function("FEEL", List.of(), null, Scope.empty());
                }
                List<String> params = bkm.parameters().stream().map(p -> p.name()).toList();
                f = interpreter.function(bkm.functionKind(), params, bkm.logic(), requirements(bkm));
                evaluating.remove(bkm.id());
                functions.put(bkm.id(), f);
            }
            return f;
        }

        /** A decision service as a function of its input decisions and input data (in that order). */
        ValFunction service(ElementView service) {
            return functions.computeIfAbsent(service.id(), id -> {
                List<RefView> params = new ArrayList<>(service.service().inputDecisions());
                params.addAll(service.service().inputData());
                List<String> names = params.stream().map(RefView::name).toList();
                return new ValFunction(Values.scalaList(names), args -> {
                    List<Val> values = CollectionConverters.asJava(args);
                    Run inner = new Run();
                    for (int i = 0; i < params.size(); i++) {
                        Val v = i < values.size() ? values.get(i) : Values.NULL;
                        if (i < service.service().inputDecisions().size()) {
                            inner.overrides.put(params.get(i).id(), v);
                        } else {
                            inner.inputs.put(params.get(i).id(), v);
                        }
                    }
                    Map<String, Val> outputs = new LinkedHashMap<>();
                    for (RefView out : service.service().outputDecisions()) {
                        ElementView d = out.local() ? elements.get(out.id()) : null;
                        if (d == null) {
                            trace().error("Output decision '" + out.name() + "' of '" + service.name() + "' not found");
                            outputs.put(out.name(), Values.NULL);
                        } else {
                            outputs.put(d.name(), inner.decision(d));
                        }
                    }
                    inner.done.forEach((decisionId, t) -> t.messages().forEach(m -> {
                        String text = service.name() + " › " + elements.get(decisionId).name() + ": " + m.text();
                        if (m.error()) {
                            trace().error(text);
                        } else {
                            trace().warning(text);
                        }
                    }));
                    return outputs.size() == 1 ? outputs.values().iterator().next() : Values.context(outputs);
                }, false);
            });
        }

        /** Every input, decision and function of the model, evaluated on first use. */
        Scope globalScope() {
            Map<String, ElementView> byName = new LinkedHashMap<>();
            for (ElementView e : elements.values()) {
                if (e.name() != null && e.kind().hasVariable()) {
                    byName.putIfAbsent(e.name(), e);
                }
            }
            return Scope.root(quotedNames, new Scope.Resolver() {
                @Override
                public Set<String> names() {
                    return byName.keySet();
                }

                @Override
                public Set<String> functionNames() {
                    Set<String> functions = new HashSet<>();
                    byName.forEach((name, e) -> {
                        if (e.kind() == ElementKind.BUSINESS_KNOWLEDGE_MODEL || e.kind() == ElementKind.DECISION_SERVICE) {
                            functions.add(name);
                        }
                    });
                    return functions;
                }

                @Override
                public Val resolve(String name) {
                    ElementView e = byName.get(name);
                    return e == null ? null : value(e);
                }
            });
        }

        /** The value an element contributes to a scope, or null for elements without one. */
        private Val value(ElementView e) {
            return switch (e.kind()) {
                case INPUT_DATA -> inputs.getOrDefault(e.id(), Values.NULL);
                case DECISION -> decision(e);
                case BUSINESS_KNOWLEDGE_MODEL -> knowledgeModel(e);
                case DECISION_SERVICE -> service(e);
                default -> null;
            };
        }
    }

    // ---- input form ----------------------------------------------------------------------------

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
                return r.failed() || !r.warnings().isEmpty() ? List.of()
                        : Values.items(r.value()).stream().map(Values::format).toList();
            }
        }
        String base = chain.isEmpty() ? typeRef : chain.getLast().typeRef();
        return "boolean".equals(base) ? List.of("true", "false") : List.of();
    }

    // ---- names ---------------------------------------------------------------------------------

    /** Every name in the model that FEEL text may refer to; those with spaces get backtick-quoted. */
    private static Set<String> names(DmnReader reader) {
        Set<String> names = new HashSet<>();
        for (ElementView e : reader.elements()) {
            names.add(e.name());
            e.parameters().forEach(p -> names.add(p.name()));
            collect(e.logic(), names);
        }
        collectTypes(reader.itemDefinitions(), names);
        names.remove(null);
        return names;
    }

    private static void collectTypes(List<ItemDefinitionView> items, Set<String> names) {
        for (ItemDefinitionView item : items) {
            names.add(item.name());
            collectTypes(item.components(), names);
        }
    }

    private static void collect(ExpressionView x, Set<String> names) {
        switch (x) {
            case null -> {
            }
            case ExpressionView.DecisionTable t -> t.outputs().forEach(o -> names.add(o.name()));
            case ExpressionView.Context c -> c.entries().forEach(e -> {
                names.add(e.name());
                collect(e.value(), names);
            });
            case ExpressionView.Relation r -> {
                r.columns().forEach(c -> names.add(c.name()));
                r.rows().forEach(row -> row.forEach(cell -> collect(cell, names)));
            }
            case ExpressionView.ListExpr l -> l.items().forEach(i -> collect(i, names));
            case ExpressionView.Invocation i -> i.bindings().forEach(b -> {
                names.add(b.name());
                collect(b.value(), names);
            });
            case ExpressionView.Function f -> {
                f.parameters().forEach(p -> names.add(p.name()));
                collect(f.body(), names);
            }
            case ExpressionView.Conditional c -> {
                collect(c.condition(), names);
                collect(c.then(), names);
                collect(c.otherwise(), names);
            }
            case ExpressionView.Iterator i -> {
                names.add(i.variable());
                collect(i.collection(), names);
                collect(i.body(), names);
            }
            case ExpressionView.Filter f -> {
                collect(f.collection(), names);
                collect(f.match(), names);
            }
            default -> {
            }
        }
    }
}
