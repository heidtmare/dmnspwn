package heidtmare.dmnspwn.edit;

/** A requested edit is not valid for the model (shown to the user as a message). */
public class DmnEditException extends RuntimeException {

    public DmnEditException(String message) {
        super(message);
    }
}
