package heidtmare.dmnspwn.edit;

import java.util.Arrays;
import java.util.List;

import heidtmare.dmnspwn.xml.DmnNamespaces;

/** Boxed expression types that can be created as decision / BKM logic. */
public enum LogicType {

    NONE(null, "None", false),
    LITERAL("literalExpression", "Literal expression", false),
    DECISION_TABLE("decisionTable", "Decision table", false),
    CONTEXT("context", "Context", false),
    RELATION("relation", "Relation", false),
    LIST("list", "List", false),
    INVOCATION("invocation", "Invocation", false),
    FUNCTION("functionDefinition", "Function definition", false),
    CONDITIONAL("conditional", "Conditional (if/then/else)", true),
    FOR("for", "Iterator: for", true),
    SOME("some", "Iterator: some", true),
    EVERY("every", "Iterator: every", true),
    FILTER("filter", "Filter", true);

    private final String localName;
    private final String displayName;
    private final boolean since14;

    LogicType(String localName, String displayName, boolean since14) {
        this.localName = localName;
        this.displayName = displayName;
        this.since14 = since14;
    }

    public String localName() {
        return localName;
    }

    public String displayName() {
        return displayName;
    }

    public static List<LogicType> available(String modelNs) {
        boolean extended = DmnNamespaces.supportsBoxedExtensions(modelNs);
        return Arrays.stream(values()).filter(t -> extended || !t.since14).toList();
    }
}
