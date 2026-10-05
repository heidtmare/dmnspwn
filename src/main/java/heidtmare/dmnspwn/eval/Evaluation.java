package heidtmare.dmnspwn.eval;

import java.util.List;

import org.camunda.feel.syntaxtree.Val;

/** The outcome of evaluating (part of) a model for one set of input values. */
public record Evaluation(List<InputResult> inputs, List<DecisionResult> decisions, ExpressionResult expression) {

    /** An error or warning raised while evaluating. */
    public record Message(boolean error, String text) {
    }

    /** A supplied input data value; {@code value} is {@code null} when the text could not be evaluated. */
    public record InputResult(String id, String name, String text, Val value, String error) {
        public String formatted() {
            return Values.format(value);
        }
    }

    /**
     * One evaluated decision. {@code matchedRules} are the 1-based rules that matched when the decision logic
     * is a decision table; {@code requested} is false for decisions only evaluated as a dependency.
     */
    public record DecisionResult(String id, String name, String typeRef, Val value, List<Message> messages,
                                 List<Integer> matchedRules, boolean table, boolean requested) {
        public String formatted() {
            return Values.format(value);
        }

        public String valueType() {
            return Values.typeName(value);
        }

        public boolean hasErrors() {
            return messages.stream().anyMatch(Message::error);
        }
    }

    public record ExpressionResult(String text, Val value, List<Message> messages) {
        public String formatted() {
            return Values.format(value);
        }

        public String valueType() {
            return Values.typeName(value);
        }

        public boolean hasErrors() {
            return messages.stream().anyMatch(Message::error);
        }
    }

    public boolean hasInputErrors() {
        return inputs.stream().anyMatch(i -> i.error() != null);
    }
}
