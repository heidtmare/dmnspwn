package heidtmare.dmnspwn.s3;

/** A user-visible S3 failure (access denied, missing object, network, or a link in the wrong state). */
public class S3StoreException extends RuntimeException {

    public S3StoreException(String message) {
        super(message);
    }
}
