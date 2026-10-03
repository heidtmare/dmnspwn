package heidtmare.dmnspwn.model;

import java.util.Optional;

/** Decision table hit policies, with their {@code hitPolicy} attribute values and single-letter codes. */
public enum HitPolicy {

    UNIQUE("UNIQUE", "U"),
    FIRST("FIRST", "F"),
    PRIORITY("PRIORITY", "P"),
    ANY("ANY", "A"),
    COLLECT("COLLECT", "C"),
    RULE_ORDER("RULE ORDER", "R"),
    OUTPUT_ORDER("OUTPUT ORDER", "O");

    private final String attribute;
    private final String code;

    HitPolicy(String attribute, String code) {
        this.attribute = attribute;
        this.code = code;
    }

    /** The value of the {@code hitPolicy} attribute. */
    public String attribute() {
        return attribute;
    }

    /** The single-letter notation from the DMN specification. */
    public String code() {
        return code;
    }

    /** The policy an attribute value stands for; a missing or blank attribute means {@link #UNIQUE}. */
    public static Optional<HitPolicy> fromAttribute(String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(UNIQUE);
        }
        for (HitPolicy p : values()) {
            if (p.attribute.equals(value.strip())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
