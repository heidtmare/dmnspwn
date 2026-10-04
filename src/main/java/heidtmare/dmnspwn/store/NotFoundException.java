package heidtmare.dmnspwn.store;

/** Something a request names (a model, element or data type) does not exist; shown as a 404 page. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
