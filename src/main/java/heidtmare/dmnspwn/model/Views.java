package heidtmare.dmnspwn.model;

import java.util.List;

/** Immutable read models produced from a DMN document for rendering. */
public final class Views {

    private Views() {
    }

    public record ModelInfo(String id, String name, String namespace, String version, String xmlNamespace,
                            String description, String expressionLanguage, String typeLanguage,
                            String exporter, String exporterVersion, boolean latest, boolean dmndiSupported,
                            boolean boxedExtensions) {
    }

    public record ImportView(String name, String namespace, String locationUri, String importType) {
    }

    /** A connector between two elements. {@code ref} identifies it for deletion even without an id. */
    public record ConnectionView(String ref, String id, ConnectionKind kind,
                                 String sourceId, String sourceName, ElementKind sourceKind, boolean sourceLocal,
                                 String targetId, String targetName, ElementKind targetKind) {
    }

    public record RefView(String id, String name, String href, boolean local) {
    }

    public record ServiceView(List<RefView> outputDecisions, List<RefView> encapsulatedDecisions,
                              List<RefView> inputDecisions, List<RefView> inputData) {
    }

    public record ParameterView(String name, String typeRef) {
    }

    public record ElementView(String id, ElementKind kind, String name, String label, String description,
                              String typeRef, String question, String allowedAnswers, String text,
                              String knowledgeType, String locationUri,
                              List<ConnectionView> requires, List<ConnectionView> requiredBy,
                              ExpressionView logic, String functionKind, List<ParameterView> parameters,
                              ServiceView service) {

        public String displayName() {
            if (label != null && !label.isBlank()) {
                return label;
            }
            if (name != null && !name.isBlank()) {
                return name;
            }
            if (text != null && !text.isBlank()) {
                return text;
            }
            return id;
        }

        public String logicName() {
            return logic == null ? "" : logic.displayName();
        }

        /** FEEL-ish signature for a BKM, e.g. {@code Installment(amount: number, rate: number)}. */
        public String signature() {
            StringBuilder sb = new StringBuilder(name == null ? "" : name).append('(');
            for (int i = 0; i < parameters.size(); i++) {
                ParameterView p = parameters.get(i);
                sb.append(i == 0 ? "" : ", ").append(p.name());
                if (p.typeRef() != null && !p.typeRef().isBlank()) {
                    sb.append(": ").append(p.typeRef());
                }
            }
            return sb.append(')').toString();
        }
    }

    public record ItemDefinitionView(String path, String id, String name, String label, String typeRef,
                                     String allowedValues, String typeConstraint, boolean collection,
                                     boolean function, List<ItemDefinitionView> components) {

        public String summary() {
            if (function) {
                return "function";
            }
            String base = !components.isEmpty() ? "structure"
                    : typeRef == null || typeRef.isBlank() ? "Any" : typeRef;
            return collection ? "list<" + base + ">" : base;
        }
    }
}
