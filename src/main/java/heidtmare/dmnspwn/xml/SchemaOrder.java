package heidtmare.dmnspwn.xml;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Child element ordering from the DMN XSD sequences, so that inserted elements
 * keep documents schema-valid. Entries in the same inner list share a rank.
 */
final class SchemaOrder {

    static final Set<String> EXPRESSIONS = Set.of(
            "literalExpression", "decisionTable", "context", "relation", "list", "invocation",
            "functionDefinition", "conditional", "for", "every", "some", "filter");

    private static final String EXPRESSION = "#expression";
    private static final Map<String, Map<String, Integer>> ORDER = new HashMap<>();

    static {
        define(List.of("definitions"),
                List.of("description"), List.of("extensionElements"), List.of("import"), List.of("itemDefinition"),
                List.of("decision", "businessKnowledgeModel", "inputData", "knowledgeSource", "decisionService"),
                List.of("textAnnotation", "association", "group"), List.of("elementCollection"),
                List.of("performanceIndicator", "organizationUnit"), List.of("DMNDI"));
        define(List.of("decision"),
                List.of("description"), List.of("extensionElements"), List.of("question"), List.of("allowedAnswers"),
                List.of("variable"), List.of("informationRequirement"), List.of("knowledgeRequirement"),
                List.of("authorityRequirement"), List.of("supportedObjective"),
                List.of("impactedPerformanceIndicator"), List.of("decisionMaker"), List.of("decisionOwner"),
                List.of("usingProcess"), List.of("usingTask"), List.of(EXPRESSION));
        define(List.of("businessKnowledgeModel"),
                List.of("description"), List.of("extensionElements"), List.of("variable"),
                List.of("encapsulatedLogic"), List.of("knowledgeRequirement"), List.of("authorityRequirement"));
        define(List.of("inputData"),
                List.of("description"), List.of("extensionElements"), List.of("variable"));
        define(List.of("knowledgeSource"),
                List.of("description"), List.of("extensionElements"), List.of("authorityRequirement"),
                List.of("type"), List.of("owner"));
        define(List.of("decisionService"),
                List.of("description"), List.of("extensionElements"), List.of("variable"), List.of("outputDecision"),
                List.of("encapsulatedDecision"), List.of("inputDecision"), List.of("inputData"));
        define(List.of("textAnnotation"),
                List.of("description"), List.of("extensionElements"), List.of("text"));
        define(List.of("association"),
                List.of("description"), List.of("extensionElements"), List.of("sourceRef"), List.of("targetRef"));
        define(List.of("decisionTable"),
                List.of("description"), List.of("extensionElements"), List.of("input"), List.of("output"),
                List.of("annotation"), List.of("rule"));
        define(List.of("input"),
                List.of("description"), List.of("extensionElements"), List.of("inputExpression"),
                List.of("inputValues"));
        define(List.of("output"),
                List.of("description"), List.of("extensionElements"), List.of("outputValues"),
                List.of("defaultOutputEntry"));
        define(List.of("rule"),
                List.of("description"), List.of("extensionElements"), List.of("inputEntry"), List.of("outputEntry"),
                List.of("annotationEntry"));
        define(List.of("itemDefinition", "itemComponent"),
                List.of("description"), List.of("extensionElements"), List.of("typeRef"), List.of("allowedValues"),
                List.of("typeConstraint"), List.of("itemComponent"), List.of("functionItem"));
        define(List.of("functionDefinition", "encapsulatedLogic"),
                List.of("description"), List.of("extensionElements"), List.of("formalParameter"),
                List.of(EXPRESSION));
        define(List.of("literalExpression", "inputExpression", "outputEntry", "defaultOutputEntry"),
                List.of("description"), List.of("extensionElements"), List.of("text"), List.of("importedValues"));
        define(List.of("inputEntry", "annotationEntry", "inputValues", "outputValues", "allowedValues",
                        "typeConstraint", "allowedAnswers", "question"),
                List.of("description"), List.of("extensionElements"), List.of("text"));
    }

    private SchemaOrder() {
    }

    @SafeVarargs
    private static void define(List<String> parents, List<String>... ranks) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < ranks.length; i++) {
            for (String name : ranks[i]) {
                order.put(name, i);
            }
        }
        parents.forEach(p -> ORDER.put(p, order));
    }

    /** Rank of a child within its parent's content model, or -1 when unknown. */
    static int rank(String parent, String child) {
        Map<String, Integer> order = ORDER.get(parent);
        if (order == null) {
            return -1;
        }
        Integer rank = order.get(child);
        if (rank == null && EXPRESSIONS.contains(child)) {
            rank = order.get(EXPRESSION);
        }
        return rank == null ? -1 : rank;
    }
}
