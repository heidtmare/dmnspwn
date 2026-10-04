package heidtmare.dmnspwn.edit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Mutable form-backing beans bound by Spring MVC (indexed properties need JavaBeans). */
public final class Forms {

    private Forms() {
    }

    private static final String SAVE = "save";

    /** A structural command of an editing form, posted in its {@code action} field. */
    public interface Command {

        /** The command as written in the form, e.g. {@code deleteRule}. */
        String key();

        /** Whether the command must name the row it applies to; other commands may name a position. */
        boolean needsRow();
    }

    /**
     * A parsed {@code action} field such as {@code deleteRule:3}: the command and its row, -1 when none is given.
     * A missing action means save; an unknown command or malformed row is rejected.
     */
    public record Action<C extends Enum<C> & Command>(C command, int row) {

        public static <C extends Enum<C> & Command> Action<C> parse(String raw, Class<C> commands) {
            String text = raw == null || raw.isBlank() ? SAVE : raw.strip();
            int colon = text.indexOf(':');
            String key = colon < 0 ? text : text.substring(0, colon);
            C command = Arrays.stream(commands.getEnumConstants()).filter(c -> c.key().equals(key)).findFirst()
                    .orElseThrow(() -> new DmnEditException("Unknown action " + key));
            int row = -1;
            if (colon >= 0) {
                try {
                    row = Integer.parseInt(text.substring(colon + 1));
                } catch (NumberFormatException e) {
                    row = -1;
                }
                if (row < 0) {
                    throw new DmnEditException("Invalid row in action " + text);
                }
            }
            if (command.needsRow() && row < 0) {
                throw new DmnEditException("Action " + key + " needs a row");
            }
            return new Action<>(command, row);
        }

        /** Whether an {@code action} field asks for a plain save, without a structural change. */
        public static boolean isSave(String raw) {
            return raw == null || raw.isBlank() || SAVE.equals(raw.strip());
        }
    }

    /** Commands of the decision table form. */
    public enum TableCommand implements Command {
        SAVE(Forms.SAVE, false),
        ADD_RULE("addRule", false),
        DUPLICATE_RULE("duplicateRule", true),
        DELETE_RULE("deleteRule", true),
        MOVE_RULE_UP("moveRuleUp", true),
        MOVE_RULE_DOWN("moveRuleDown", true),
        ADD_INPUT("addInput", false),
        DELETE_INPUT("deleteInput", true),
        ADD_OUTPUT("addOutput", false),
        DELETE_OUTPUT("deleteOutput", true),
        ADD_ANNOTATION("addAnnotation", false),
        DELETE_ANNOTATION("deleteAnnotation", true);

        private final String key;
        private final boolean needsRow;

        TableCommand(String key, boolean needsRow) {
            this.key = key;
            this.needsRow = needsRow;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public boolean needsRow() {
            return needsRow;
        }
    }

    /** Commands of the business knowledge model parameters form. */
    public enum ParameterCommand implements Command {
        SAVE(Forms.SAVE, false),
        ADD_PARAMETER("addParameter", false),
        DELETE_PARAMETER("deleteParameter", true),
        MOVE_PARAMETER_UP("moveParameterUp", true);

        private final String key;
        private final boolean needsRow;

        ParameterCommand(String key, boolean needsRow) {
            this.key = key;
            this.needsRow = needsRow;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public boolean needsRow() {
            return needsRow;
        }
    }

    /** Commands of the item definition form. */
    public enum ComponentCommand implements Command {
        SAVE(Forms.SAVE, false),
        ADD_COMPONENT("addComponent", false),
        DELETE_COMPONENT("deleteComponent", true),
        MOVE_COMPONENT_UP("moveComponentUp", true);

        private final String key;
        private final boolean needsRow;

        ComponentCommand(String key, boolean needsRow) {
            this.key = key;
            this.needsRow = needsRow;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public boolean needsRow() {
            return needsRow;
        }
    }

    public static class ElementForm {
        private String name;
        private String label;
        private String description;
        private String typeRef;
        private String question;
        private String allowedAnswers;
        private String text;
        private String knowledgeType;
        private String locationUri;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTypeRef() { return typeRef; }
        public void setTypeRef(String typeRef) { this.typeRef = typeRef; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAllowedAnswers() { return allowedAnswers; }
        public void setAllowedAnswers(String allowedAnswers) { this.allowedAnswers = allowedAnswers; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getKnowledgeType() { return knowledgeType; }
        public void setKnowledgeType(String knowledgeType) { this.knowledgeType = knowledgeType; }
        public String getLocationUri() { return locationUri; }
        public void setLocationUri(String locationUri) { this.locationUri = locationUri; }
    }

    public static class DecisionTableForm {
        private String hitPolicy;
        private String aggregation;
        private String outputLabel;
        private List<InputColumn> inputs = new ArrayList<>();
        private List<OutputColumn> outputs = new ArrayList<>();
        private List<String> annotations = new ArrayList<>();
        private List<RuleRow> rules = new ArrayList<>();
        private String action;

        public String getHitPolicy() { return hitPolicy; }
        public void setHitPolicy(String hitPolicy) { this.hitPolicy = hitPolicy; }
        public String getAggregation() { return aggregation; }
        public void setAggregation(String aggregation) { this.aggregation = aggregation; }
        public String getOutputLabel() { return outputLabel; }
        public void setOutputLabel(String outputLabel) { this.outputLabel = outputLabel; }
        public List<InputColumn> getInputs() { return inputs; }
        public void setInputs(List<InputColumn> inputs) { this.inputs = inputs; }
        public List<OutputColumn> getOutputs() { return outputs; }
        public void setOutputs(List<OutputColumn> outputs) { this.outputs = outputs; }
        public List<String> getAnnotations() { return annotations; }
        public void setAnnotations(List<String> annotations) { this.annotations = annotations; }
        public List<RuleRow> getRules() { return rules; }
        public void setRules(List<RuleRow> rules) { this.rules = rules; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    public static class InputColumn {
        private String label;
        private String expression;
        private String typeRef;
        private String inputValues;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getExpression() { return expression; }
        public void setExpression(String expression) { this.expression = expression; }
        public String getTypeRef() { return typeRef; }
        public void setTypeRef(String typeRef) { this.typeRef = typeRef; }
        public String getInputValues() { return inputValues; }
        public void setInputValues(String inputValues) { this.inputValues = inputValues; }
    }

    public static class OutputColumn {
        private String name;
        private String label;
        private String typeRef;
        private String outputValues;
        private String defaultOutput;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getTypeRef() { return typeRef; }
        public void setTypeRef(String typeRef) { this.typeRef = typeRef; }
        public String getOutputValues() { return outputValues; }
        public void setOutputValues(String outputValues) { this.outputValues = outputValues; }
        public String getDefaultOutput() { return defaultOutput; }
        public void setDefaultOutput(String defaultOutput) { this.defaultOutput = defaultOutput; }
    }

    public static class RuleRow {
        private List<String> inputs = new ArrayList<>();
        private List<String> outputs = new ArrayList<>();
        private List<String> annotations = new ArrayList<>();
        private String description;

        public List<String> getInputs() { return inputs; }
        public void setInputs(List<String> inputs) { this.inputs = inputs; }
        public List<String> getOutputs() { return outputs; }
        public void setOutputs(List<String> outputs) { this.outputs = outputs; }
        public List<String> getAnnotations() { return annotations; }
        public void setAnnotations(List<String> annotations) { this.annotations = annotations; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class ItemDefinitionForm {
        private String name;
        private String label;
        private String typeRef;
        private String allowedValues;
        private boolean collection;
        private List<ComponentRow> components = new ArrayList<>();
        private String action;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getTypeRef() { return typeRef; }
        public void setTypeRef(String typeRef) { this.typeRef = typeRef; }
        public String getAllowedValues() { return allowedValues; }
        public void setAllowedValues(String allowedValues) { this.allowedValues = allowedValues; }
        public boolean isCollection() { return collection; }
        public void setCollection(boolean collection) { this.collection = collection; }
        public List<ComponentRow> getComponents() { return components; }
        public void setComponents(List<ComponentRow> components) { this.components = components; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    public static class ComponentRow {
        private String name;
        private String typeRef;
        private String allowedValues;
        private boolean collection;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTypeRef() { return typeRef; }
        public void setTypeRef(String typeRef) { this.typeRef = typeRef; }
        public String getAllowedValues() { return allowedValues; }
        public void setAllowedValues(String allowedValues) { this.allowedValues = allowedValues; }
        public boolean isCollection() { return collection; }
        public void setCollection(boolean collection) { this.collection = collection; }
    }

    public static class ParametersForm {
        private String functionKind;
        private List<ParameterRow> parameters = new ArrayList<>();
        private String action;

        public String getFunctionKind() { return functionKind; }
        public void setFunctionKind(String functionKind) { this.functionKind = functionKind; }
        public List<ParameterRow> getParameters() { return parameters; }
        public void setParameters(List<ParameterRow> parameters) { this.parameters = parameters; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    public static class ParameterRow {
        private String name;
        private String typeRef;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTypeRef() { return typeRef; }
        public void setTypeRef(String typeRef) { this.typeRef = typeRef; }
    }

    public static class ServiceForm {
        private List<String> outputDecisions = new ArrayList<>();
        private List<String> encapsulatedDecisions = new ArrayList<>();
        private List<String> inputDecisions = new ArrayList<>();
        private List<String> inputData = new ArrayList<>();

        public List<String> getOutputDecisions() { return outputDecisions; }
        public void setOutputDecisions(List<String> outputDecisions) { this.outputDecisions = outputDecisions; }
        public List<String> getEncapsulatedDecisions() { return encapsulatedDecisions; }
        public void setEncapsulatedDecisions(List<String> encapsulatedDecisions) { this.encapsulatedDecisions = encapsulatedDecisions; }
        public List<String> getInputDecisions() { return inputDecisions; }
        public void setInputDecisions(List<String> inputDecisions) { this.inputDecisions = inputDecisions; }
        public List<String> getInputData() { return inputData; }
        public void setInputData(List<String> inputData) { this.inputData = inputData; }
    }
}
