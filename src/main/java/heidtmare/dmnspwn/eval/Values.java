package heidtmare.dmnspwn.eval;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.camunda.feel.context.Context;
import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValBoolean;
import org.camunda.feel.syntaxtree.ValContext;
import org.camunda.feel.syntaxtree.ValDate;
import org.camunda.feel.syntaxtree.ValDateTime;
import org.camunda.feel.syntaxtree.ValDayTimeDuration;
import org.camunda.feel.syntaxtree.ValError;
import org.camunda.feel.syntaxtree.ValFatalError;
import org.camunda.feel.syntaxtree.ValFunction;
import org.camunda.feel.syntaxtree.ValList;
import org.camunda.feel.syntaxtree.ValLocalDateTime;
import org.camunda.feel.syntaxtree.ValLocalTime;
import org.camunda.feel.syntaxtree.ValNull$;
import org.camunda.feel.syntaxtree.ValNumber;
import org.camunda.feel.syntaxtree.ValString;
import org.camunda.feel.syntaxtree.ValTime;
import org.camunda.feel.syntaxtree.ValYearMonthDuration;

import scala.jdk.javaapi.CollectionConverters;

/** Building, inspecting and printing FEEL values from Java. */
public final class Values {

    public static final Val NULL = ValNull$.MODULE$;

    private Values() {
    }

    public static boolean isNull(Val v) {
        return v == null || v == NULL;
    }

    public static boolean isTrue(Val v) {
        return v instanceof ValBoolean b && b.value();
    }

    public static Val bool(boolean value) {
        return new ValBoolean(value);
    }

    public static Val number(long value) {
        return Feel.VAL_MAPPER.toVal(BigDecimal.valueOf(value));
    }

    public static Val list(List<Val> items) {
        return new ValList((scala.collection.immutable.Seq<Val>) scalaList(items));
    }

    public static List<Val> items(Val list) {
        return list instanceof ValList l ? CollectionConverters.asJava(l.itemsAsSeq()) : List.of(list);
    }

    /** An ordered FEEL context. */
    public static Val context(Map<String, Val> entries) {
        scala.collection.immutable.Map<String, Object> vars =
                scala.collection.immutable.ListMap.from(CollectionConverters.asScala(new LinkedHashMap<String, Object>(entries)));
        return new ValContext(new Context.StaticContext(vars, scala.collection.immutable.Map.from(
                CollectionConverters.asScala(Map.<String, scala.collection.immutable.List<ValFunction>>of()))));
    }

    /** Context entries in their original order. */
    public static Map<String, Val> entries(ValContext context) {
        Map<String, Val> result = new LinkedHashMap<>();
        CollectionConverters.asJava(context.context().variableProvider().getVariables())
                .forEach((k, v) -> result.put(k, v instanceof Val val ? val : Feel.VAL_MAPPER.toVal(v)));
        return result;
    }

    public static <T> scala.collection.immutable.List<T> scalaList(List<T> items) {
        return scala.collection.immutable.List.from(CollectionConverters.asScala(items));
    }

    /** FEEL equality as used by decision tables (ANY hit policy, distinct counts, output priorities). */
    public static boolean same(Val a, Val b) {
        if (isNull(a) || isNull(b)) {
            return isNull(a) && isNull(b);
        }
        if (a instanceof ValNumber x && b instanceof ValNumber y) {
            return x.value().bigDecimal().compareTo(y.value().bigDecimal()) == 0;
        }
        if (a instanceof ValList x && b instanceof ValList y) {
            List<Val> xs = items(x);
            List<Val> ys = items(y);
            if (xs.size() != ys.size()) {
                return false;
            }
            for (int i = 0; i < xs.size(); i++) {
                if (!same(xs.get(i), ys.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof ValContext x && b instanceof ValContext y) {
            Map<String, Val> xs = entries(x);
            Map<String, Val> ys = entries(y);
            return xs.keySet().equals(ys.keySet())
                    && xs.entrySet().stream().allMatch(e -> same(e.getValue(), ys.get(e.getKey())));
        }
        return Objects.equals(a, b);
    }

    // ---- printing --------------------------------------------------------------------------------

    /** The value as FEEL source, e.g. {@code {amount: 1000, due: date("2024-01-31")}}. */
    public static String format(Val v) {
        StringBuilder sb = new StringBuilder();
        format(v, sb);
        return sb.toString();
    }

    private static void format(Val v, StringBuilder sb) {
        switch (v) {
            case null -> sb.append("null");
            case ValNumber n -> sb.append(plain(n.value().bigDecimal()));
            case ValString s -> quote(s.value(), sb);
            case ValBoolean b -> sb.append(b.value());
            case ValDate d -> sb.append("date(\"").append(d).append("\")");
            case ValLocalTime t -> sb.append("time(\"").append(t).append("\")");
            case ValTime t -> sb.append("time(\"").append(t).append("\")");
            case ValLocalDateTime t -> sb.append("date and time(\"").append(t).append("\")");
            case ValDateTime t -> sb.append("date and time(\"").append(t).append("\")");
            case ValDayTimeDuration d -> sb.append("duration(\"").append(d).append("\")");
            case ValYearMonthDuration d -> sb.append("duration(\"").append(d).append("\")");
            case ValList l -> {
                sb.append('[');
                List<Val> items = items(l);
                for (int i = 0; i < items.size(); i++) {
                    sb.append(i == 0 ? "" : ", ");
                    format(items.get(i), sb);
                }
                sb.append(']');
            }
            case ValContext c -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Val> e : entries(c).entrySet()) {
                    sb.append(first ? "" : ", ");
                    first = false;
                    if (Feel.isIdentifier(e.getKey())) {
                        sb.append(e.getKey());
                    } else {
                        quote(e.getKey(), sb);
                    }
                    sb.append(": ");
                    format(e.getValue(), sb);
                }
                sb.append('}');
            }
            case ValFunction f -> sb.append("function(")
                    .append(String.join(", ", CollectionConverters.asJava(f.params()))).append(')');
            case ValError e -> sb.append("error: ").append(e.error());
            case ValFatalError e -> sb.append("error: ").append(e.error());
            default -> sb.append(isNull(v) ? "null" : v.toString());
        }
    }

    private static String plain(BigDecimal d) {
        String s = d.stripTrailingZeros().toPlainString();
        return "-0".equals(s) ? "0" : s;
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    /** A short type name for display, e.g. {@code number}, {@code list}. */
    public static String typeName(Val v) {
        return switch (v) {
            case null -> "null";
            case ValNumber n -> "number";
            case ValString s -> "string";
            case ValBoolean b -> "boolean";
            case ValDate d -> "date";
            case ValLocalTime t -> "time";
            case ValTime t -> "time";
            case ValLocalDateTime t -> "date and time";
            case ValDateTime t -> "date and time";
            case ValDayTimeDuration d -> "days and time duration";
            case ValYearMonthDuration d -> "years and months duration";
            case ValList l -> "list";
            case ValContext c -> "context";
            case ValFunction f -> "function";
            default -> isNull(v) ? "null" : "";
        };
    }
}
