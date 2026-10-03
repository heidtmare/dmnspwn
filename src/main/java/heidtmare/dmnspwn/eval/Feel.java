package heidtmare.dmnspwn.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.camunda.feel.api.EvaluationFailure;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.camunda.feel.api.ParseResult;
import org.camunda.feel.syntaxtree.ParsedExpression;
import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValNull$;
import org.camunda.feel.valuemapper.ValueMapper;
import org.springframework.stereotype.Component;

/**
 * FEEL evaluation backed by the FEEL Scala engine (Apache 2.0).
 *
 * <p>The engine is configured to hand back its own {@link Val} values unchanged, so numbers stay decimals and
 * dates, durations, contexts and functions keep their FEEL semantics between boxed expressions. DMN allows
 * variable names with spaces and other punctuation ({@code Guest Count}, {@code Guests with children?}) that the
 * engine only understands in backticks, so known names are quoted before parsing (see {@link #quoteNames}).
 */
@Component
public class Feel {

    private static final int CACHE_LIMIT = 10_000;

    private static final ValueMapper DEFAULT_MAPPER = ValueMapper.defaultValueMapper();

    /** Wraps plain Java values into {@link Val}s but never unwraps results. */
    static final ValueMapper VAL_MAPPER = new ValueMapper() {
        @Override
        public Val toVal(Object value) {
            return DEFAULT_MAPPER.toVal(value);
        }

        @Override
        public Object unpackVal(Val value) {
            return value;
        }
    };

    private final FeelEngineApi engine = FeelEngineBuilder.create().withValueMapper(VAL_MAPPER).build();
    private final Map<Key, Parsed> expressions = new ConcurrentHashMap<>();
    private final Map<Key, Parsed> unaryTests = new ConcurrentHashMap<>();

    /** Outcome of one evaluation: a value (FEEL {@code null} on failure), a fatal error and/or warnings. */
    public record Result(Val value, String error, List<String> warnings) {
        public boolean failed() {
            return error != null;
        }
    }

    private record Parsed(ParsedExpression expression, String error) {
    }

    /** Cache key: the raw text and the names it is quoted against, so quoting only runs on a miss. */
    private record Key(QuotedNames names, String text) {
    }

    public Result evaluate(String expression, Scope scope) {
        Parsed parsed = parse(expressions, expression, scope.quotedNames(), false);
        if (parsed.error() != null) {
            return failure(parsed.error());
        }
        return result(engine.evaluate(parsed.expression(), scope));
    }

    /** Evaluates unary tests (a decision table input entry) against {@code input}; {@code -} matches anything. */
    public Result test(String unaryTests, Val input, Scope scope) {
        Parsed parsed = parse(this.unaryTests, unaryTests, scope.quotedNames(), true);
        if (parsed.error() != null) {
            return failure(parsed.error());
        }
        return result(engine.evaluateWithInput(parsed.expression(), input, scope));
    }

    /** A syntax error message, or {@code null} when the text parses as a FEEL expression. */
    public String syntaxError(String expression, Collection<String> names) {
        return parse(expressions, expression, QuotedNames.of(names), false).error();
    }

    private Parsed parse(Map<Key, Parsed> cache, String text, QuotedNames names, boolean tests) {
        Key key = new Key(names, text);
        Parsed parsed = cache.get(key);
        if (parsed == null) {
            String quoted = quoteNames(text, names);
            ParseResult result = tests ? engine.parseUnaryTests(quoted) : engine.parseExpression(quoted);
            parsed = result.isSuccess()
                    ? new Parsed(result.parsedExpression(), null)
                    : new Parsed(null, result.failure().message());
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
            cache.put(key, parsed);
        }
        return parsed;
    }

    private static Result result(EvaluationResult r) {
        List<String> warnings = new ArrayList<>();
        for (EvaluationFailure f : r.getSuppressedFailures()) {
            warnings.add(f.failureMessage());
        }
        if (!r.isSuccess()) {
            return new Result(ValNull$.MODULE$, r.failure().message(), warnings);
        }
        Object value = r.result();
        return new Result(value instanceof Val v ? v : VAL_MAPPER.toVal(value), null, warnings);
    }

    private static Result failure(String message) {
        return new Result(ValNull$.MODULE$, message, List.of());
    }

    // ---- names with spaces -------------------------------------------------------------------------

    /**
     * Names that are not plain FEEL identifiers, as whitespace-tolerant patterns, longest first. Equal when the
     * names are, so parse results are shared between evaluators of the same model.
     */
    public static final class QuotedNames {

        static final QuotedNames NONE = new QuotedNames(List.of(), List.of());

        private final List<String> names;
        private final List<Pattern> patterns;
        private final int hash;

        private QuotedNames(List<String> names, List<Pattern> patterns) {
            this.names = names;
            this.patterns = patterns;
            this.hash = names.hashCode();
        }

        public static QuotedNames of(Collection<String> names) {
            List<String> special = names.stream()
                    .filter(n -> n != null && !n.isBlank() && !isIdentifier(n.strip()) && n.indexOf('`') < 0)
                    .map(String::strip).distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed()).toList();
            List<Pattern> patterns = new ArrayList<>(special.size());
            for (String name : special) {
                StringBuilder regex = new StringBuilder();
                for (String word : name.split("\\s+")) {
                    regex.append(regex.isEmpty() ? "" : "\\s+").append(Pattern.quote(word));
                }
                patterns.add(Pattern.compile(regex.toString()));
            }
            return new QuotedNames(special, patterns);
        }

        boolean isEmpty() {
            return patterns.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof QuotedNames q && hash == q.hash && names.equals(q.names);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static boolean isIdentifier(String name) {
        if (name.isEmpty() || !isNameStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isNamePart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '?';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '?';
    }

    /**
     * Puts backticks around occurrences of the given names, skipping string literals, comments and already
     * quoted names. Matches must start and end on a name boundary, so {@code Applicant Age} is not found inside
     * {@code Applicant Ages}.
     */
    static String quoteNames(String text, QuotedNames names) {
        if (text == null || names.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        Matcher[] matchers = names.patterns.stream().map(p -> p.matcher(text)).toArray(Matcher[]::new);
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int skip = skipLiteral(text, i);
            if (skip > i) {
                out.append(text, i, skip);
                i = skip;
                continue;
            }
            if (i == 0 || !isNamePart(text.charAt(i - 1))) {
                int end = -1;
                for (Matcher m : matchers) {
                    m.region(i, n);
                    if (m.lookingAt() && (m.end() == n || !isNamePart(text.charAt(m.end()))
                            || !isNamePart(text.charAt(m.end() - 1)))) {
                        end = m.end();
                        break;
                    }
                }
                if (end > 0) {
                    out.append('`').append(text.substring(i, end).replaceAll("\\s+", " ")).append('`');
                    i = end;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** End index of a string literal, comment or backtick name starting at {@code i}, or {@code i}. */
    private static int skipLiteral(String text, int i) {
        char c = text.charAt(i);
        int n = text.length();
        if (c == '"') {
            int j = i + 1;
            while (j < n && text.charAt(j) != '"') {
                j += text.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(j + 1, n);
        }
        if (c == '`') {
            int j = text.indexOf('`', i + 1);
            return j < 0 ? n : j + 1;
        }
        if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
            int j = text.indexOf('\n', i);
            return j < 0 ? n : j;
        }
        if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
            int j = text.indexOf("*/", i + 2);
            return j < 0 ? n : j + 2;
        }
        return i;
    }
}
