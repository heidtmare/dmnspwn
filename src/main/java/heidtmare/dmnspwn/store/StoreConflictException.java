package heidtmare.dmnspwn.store;

/** A conditional write failed: the file was changed (or created) by someone else since it was read. */
public class StoreConflictException extends RuntimeException {

    public StoreConflictException(String message) {
        super(message);
    }
}
