package heidtmare.dmnspwn.model;

import java.util.List;

/** Boxed expressions as read from the model, rendered recursively by the view layer. */
public sealed interface ExpressionView {

    String id();

    String typeRef();

    /** Template discriminator. */
    String type();

    String displayName();

    record Literal(String id, String typeRef, String text, String language) implements ExpressionView {
        public String type() {
            return "literal";
        }

        public String displayName() {
            return "Literal expression";
        }
    }

    record InputClause(String id, String label, String expression, String typeRef, String inputValues) {
        public String heading() {
            return label != null && !label.isBlank() ? label : expression;
        }
    }

    record OutputClause(String id, String name, String label, String typeRef, String outputValues,
                        String defaultOutput) {
        public String heading() {
            return label != null && !label.isBlank() ? label : name;
        }
    }

    record Rule(String id, List<String> inputs, List<String> outputs, List<String> annotations,
                String description) {
    }

    record DecisionTable(String id, String typeRef, String hitPolicy, String aggregation, String outputLabel,
                         String orientation, List<InputClause> inputs, List<OutputClause> outputs,
                         List<String> annotations, List<Rule> rules) implements ExpressionView {
        public String type() {
            return "decisionTable";
        }

        public String displayName() {
            return "Decision table";
        }

        /** Single-letter hit policy notation from the DMN specification (e.g. {@code U}, {@code C+}). */
        public String hitPolicyCode() {
            String hp = hitPolicy == null || hitPolicy.isBlank() ? "UNIQUE" : hitPolicy;
            String code = switch (hp) {
                case "ANY" -> "A";
                case "PRIORITY" -> "P";
                case "FIRST" -> "F";
                case "OUTPUT ORDER" -> "O";
                case "RULE ORDER" -> "R";
                case "COLLECT" -> "C";
                default -> "U";
            };
            if ("C".equals(code) && aggregation != null) {
                code += switch (aggregation) {
                    case "SUM" -> "+";
                    case "COUNT" -> "#";
                    case "MIN" -> "<";
                    case "MAX" -> ">";
                    default -> "";
                };
            }
            return code;
        }

        public String hitPolicyLabel() {
            String hp = hitPolicy == null || hitPolicy.isBlank() ? "UNIQUE" : hitPolicy;
            return aggregation == null || aggregation.isBlank() ? hp : hp + " " + aggregation;
        }
    }

    record ContextEntry(String name, String typeRef, ExpressionView value) {
    }

    record Context(String id, String typeRef, List<ContextEntry> entries) implements ExpressionView {
        public String type() {
            return "context";
        }

        public String displayName() {
            return "Context";
        }
    }

    record Column(String name, String typeRef) {
    }

    record Relation(String id, String typeRef, List<Column> columns, List<List<ExpressionView>> rows)
            implements ExpressionView {
        public String type() {
            return "relation";
        }

        public String displayName() {
            return "Relation";
        }
    }

    record ListExpr(String id, String typeRef, List<ExpressionView> items) implements ExpressionView {
        public String type() {
            return "list";
        }

        public String displayName() {
            return "List";
        }
    }

    record Binding(String name, String typeRef, ExpressionView value) {
    }

    record Invocation(String id, String typeRef, String function, List<Binding> bindings) implements ExpressionView {
        public String type() {
            return "invocation";
        }

        public String displayName() {
            return "Invocation";
        }
    }

    record Parameter(String name, String typeRef) {
    }

    record Function(String id, String typeRef, String kind, List<Parameter> parameters, ExpressionView body)
            implements ExpressionView {
        public String type() {
            return "function";
        }

        public String displayName() {
            return "Function definition";
        }

        public String kindCode() {
            return kind == null || kind.isBlank() ? "F" : kind.substring(0, 1).toUpperCase();
        }
    }

    record Conditional(String id, String typeRef, ExpressionView condition, ExpressionView then,
                       ExpressionView otherwise) implements ExpressionView {
        public String type() {
            return "conditional";
        }

        public String displayName() {
            return "Conditional";
        }
    }

    /** {@code for}, {@code some} and {@code every} boxed iterators. */
    record Iterator(String id, String typeRef, String keyword, String variable, ExpressionView collection,
                    String bodyKeyword, ExpressionView body) implements ExpressionView {
        public String type() {
            return "iterator";
        }

        public String displayName() {
            return "Iterator (" + keyword + ")";
        }
    }

    record Filter(String id, String typeRef, ExpressionView collection, ExpressionView match) implements ExpressionView {
        public String type() {
            return "filter";
        }

        public String displayName() {
            return "Filter";
        }
    }

    /** Any expression type this viewer does not render structurally; shown as XML. */
    record Unknown(String id, String typeRef, String localName, String xml) implements ExpressionView {
        public String type() {
            return "unknown";
        }

        public String displayName() {
            return localName;
        }
    }
}
