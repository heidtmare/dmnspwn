package heidtmare.dmnspwn.eval;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.feel.context.Context;
import org.camunda.feel.context.FunctionProvider;
import org.camunda.feel.context.VariableProvider;
import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValFunction;

import scala.Option;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Variables visible to a FEEL expression. Scopes nest (a context entry sees the entries before it and the
 * decision's requirements); function-valued variables can also be invoked by name, e.g. a BKM.
 */
public final class Scope implements Context {

    /** Supplies variables on first use, e.g. decisions that are only evaluated when an expression needs them. */
    public interface Resolver {
        Set<String> names();

        /** The value of {@code name}, or {@code null} when it is unknown. */
        Val resolve(String name);

        /** Names whose value is a function, known without resolving them. */
        default Set<String> functionNames() {
            return Set.of();
        }
    }

    private final Scope parent;
    private final Feel.QuotedNames quotedNames;
    private final Resolver resolver;
    private final Map<String, Val> variables = new LinkedHashMap<>();

    private Scope(Scope parent, Feel.QuotedNames quotedNames, Resolver resolver) {
        this.parent = parent;
        this.quotedNames = quotedNames;
        this.resolver = resolver;
    }

    /** A root scope; {@code quotedNames} are the names to backtick-quote in expressions evaluated here. */
    public static Scope root(Feel.QuotedNames quotedNames) {
        return new Scope(null, quotedNames, null);
    }

    /** A root scope whose variables come from {@code resolver}. */
    public static Scope root(Feel.QuotedNames quotedNames, Resolver resolver) {
        return new Scope(null, quotedNames, resolver);
    }

    public static Scope empty() {
        return root(Feel.QuotedNames.NONE);
    }

    public Scope child() {
        return new Scope(this, quotedNames, null);
    }

    public Scope put(String name, Val value) {
        variables.put(name, value);
        return this;
    }

    public Val get(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            Val v = s.variables.get(name);
            if (v != null || s.variables.containsKey(name)) {
                return v;
            }
            if (s.resolver != null) {
                v = s.resolver.resolve(name);
                if (v != null) {
                    s.variables.put(name, v);
                    return v;
                }
            }
        }
        return null;
    }

    Feel.QuotedNames quotedNames() {
        return quotedNames;
    }

    private Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (Scope s = this; s != null; s = s.parent) {
            names.addAll(s.variables.keySet());
            if (s.resolver != null) {
                names.addAll(s.resolver.names());
            }
        }
        return names;
    }

    /** Visible names bound to functions; unresolved names are only asked about, never evaluated. */
    private Set<String> functionNames() {
        Set<String> seen = new HashSet<>();
        Set<String> functions = new LinkedHashSet<>();
        for (Scope s = this; s != null; s = s.parent) {
            s.variables.forEach((name, value) -> {
                if (seen.add(name) && value instanceof ValFunction) {
                    functions.add(name);
                }
            });
            if (s.resolver != null) {
                Set<String> resolvable = s.resolver.functionNames();
                for (String name : s.resolver.names()) {
                    if (seen.add(name) && resolvable.contains(name)) {
                        functions.add(name);
                    }
                }
            }
        }
        return functions;
    }

    // ---- FEEL engine context -------------------------------------------------------------------

    @Override
    public VariableProvider variableProvider() {
        return new VariableProvider() {
            @Override
            public Option<Object> getVariable(String name) {
                return Option.apply(get(name));
            }

            @Override
            public scala.collection.Iterable<String> keys() {
                return CollectionConverters.asScala(names());
            }
        };
    }

    @Override
    public FunctionProvider functionProvider() {
        return new FunctionProvider() {
            @Override
            public scala.collection.immutable.List<ValFunction> getFunctions(String name) {
                List<ValFunction> functions = get(name) instanceof ValFunction f ? List.of(f) : List.of();
                return Values.scalaList(functions);
            }

            @Override
            public scala.collection.Iterable<String> functionNames() {
                return CollectionConverters.asScala(Scope.this.functionNames());
            }
        };
    }
}
