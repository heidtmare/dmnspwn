package heidtmare.dmnspwn.model;

import java.util.Arrays;
import java.util.Optional;

/** The DRG elements and artifacts that appear as nodes in a decision requirements diagram. */
public enum ElementKind {

    DECISION("decision", "Decision", 180, 80),
    INPUT_DATA("inputData", "Input Data", 180, 60),
    BUSINESS_KNOWLEDGE_MODEL("businessKnowledgeModel", "Business Knowledge Model", 180, 80),
    KNOWLEDGE_SOURCE("knowledgeSource", "Knowledge Source", 180, 80),
    DECISION_SERVICE("decisionService", "Decision Service", 320, 240),
    TEXT_ANNOTATION("textAnnotation", "Text Annotation", 180, 60);

    private final String localName;
    private final String displayName;
    private final double width;
    private final double height;

    ElementKind(String localName, String displayName, double width, double height) {
        this.localName = localName;
        this.displayName = displayName;
        this.width = width;
        this.height = height;
    }

    public static Optional<ElementKind> fromLocalName(String localName) {
        return Arrays.stream(values()).filter(k -> k.localName.equals(localName)).findFirst();
    }

    public String localName() {
        return localName;
    }

    public String displayName() {
        return displayName;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    /** CSS-friendly identifier, e.g. {@code input-data}. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    public boolean isDrgElement() {
        return this != TEXT_ANNOTATION;
    }

    /** Elements carrying a {@code <variable>} information item. */
    public boolean hasVariable() {
        return this == DECISION || this == INPUT_DATA || this == BUSINESS_KNOWLEDGE_MODEL
                || this == DECISION_SERVICE;
    }

    public boolean hasLogic() {
        return this == DECISION || this == BUSINESS_KNOWLEDGE_MODEL;
    }
}
