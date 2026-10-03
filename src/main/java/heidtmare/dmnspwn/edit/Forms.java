package heidtmare.dmnspwn.edit;

import java.util.ArrayList;
import java.util.List;

/** Mutable form-backing beans bound by Spring MVC (indexed properties need JavaBeans). */
public final class Forms {

    private Forms() {
    }

    /** Parses structural actions such as {@code deleteRule:3}. */
    public record Action(String name, int index) {
        public static Action parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return new Action("save", -1);
            }
            int colon = raw.indexOf(':');
            if (colon < 0) {
                return new Action(raw, -1);
            }
            try {
                return new Action(raw.substring(0, colon), Integer.parseInt(raw.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return new Action(raw.substring(0, colon), -1);
            }
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
