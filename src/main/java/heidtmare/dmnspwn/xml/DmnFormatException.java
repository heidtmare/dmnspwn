package heidtmare.dmnspwn.xml;

/** Thrown when content is not well-formed XML or not a DMN document. */
public class DmnFormatException extends RuntimeException {

    public DmnFormatException(String message) {
        super(message);
    }

    public DmnFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
