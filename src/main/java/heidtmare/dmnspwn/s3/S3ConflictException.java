package heidtmare.dmnspwn.s3;

/** The remote object changed (or already exists) since this model last synchronised with it. */
public class S3ConflictException extends S3StoreException {

    private final String key;

    public S3ConflictException(String key, String message) {
        super(message);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
