package heidtmare.dmnspwn.validate;

/** A finding of the model validator. {@code elementId} is null for model-wide issues. */
public record Issue(Severity severity, String elementId, String elementName, String message) {

    public enum Severity {
        ERROR, WARNING
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
