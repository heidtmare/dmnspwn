package heidtmare.dmnspwn.model;

import java.util.Optional;

/** DRD connectors: the three requirement kinds and annotation associations. */
public enum ConnectionKind {

    INFORMATION("informationRequirement", "Information requirement"),
    KNOWLEDGE("knowledgeRequirement", "Knowledge requirement"),
    AUTHORITY("authorityRequirement", "Authority requirement"),
    ASSOCIATION("association", "Association");

    private final String localName;
    private final String displayName;

    ConnectionKind(String localName, String displayName) {
        this.localName = localName;
        this.displayName = displayName;
    }

    public String localName() {
        return localName;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this is one of the requirement kinds, which build the requirement graph. */
    public boolean isRequirement() {
        return this != ASSOCIATION;
    }

    public String slug() {
        return name().toLowerCase();
    }

    public static Optional<ConnectionKind> fromLocalName(String localName) {
        for (ConnectionKind k : values()) {
            if (k.localName.equals(localName)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /**
     * The connector allowed from a required element ({@code source}) to the element that
     * requires it ({@code target}), per the DMN DRG connection rules.
     */
    public static Optional<ConnectionKind> between(ElementKind source, ElementKind target) {
        if (source == ElementKind.TEXT_ANNOTATION || target == ElementKind.TEXT_ANNOTATION) {
            return source == target ? Optional.empty() : Optional.of(ASSOCIATION);
        }
        return switch (target) {
            case DECISION -> switch (source) {
                case DECISION, INPUT_DATA -> Optional.of(INFORMATION);
                case BUSINESS_KNOWLEDGE_MODEL, DECISION_SERVICE -> Optional.of(KNOWLEDGE);
                case KNOWLEDGE_SOURCE -> Optional.of(AUTHORITY);
                default -> Optional.empty();
            };
            case BUSINESS_KNOWLEDGE_MODEL -> switch (source) {
                case BUSINESS_KNOWLEDGE_MODEL, DECISION_SERVICE -> Optional.of(KNOWLEDGE);
                case KNOWLEDGE_SOURCE -> Optional.of(AUTHORITY);
                default -> Optional.empty();
            };
            case KNOWLEDGE_SOURCE -> switch (source) {
                case DECISION, INPUT_DATA, KNOWLEDGE_SOURCE -> Optional.of(AUTHORITY);
                default -> Optional.empty();
            };
            default -> Optional.empty();
        };
    }

    /** Name of the reference child element inside a requirement for the given source kind. */
    public String referenceElement(ElementKind source) {
        return switch (this) {
            case INFORMATION -> source == ElementKind.INPUT_DATA ? "requiredInput" : "requiredDecision";
            case KNOWLEDGE -> "requiredKnowledge";
            case AUTHORITY -> switch (source) {
                case INPUT_DATA -> "requiredInput";
                case DECISION -> "requiredDecision";
                default -> "requiredAuthority";
            };
            case ASSOCIATION -> "sourceRef";
        };
    }
}
