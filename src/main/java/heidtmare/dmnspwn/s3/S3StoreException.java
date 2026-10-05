package heidtmare.dmnspwn.s3;

/** A user-visible S3 failure (access denied, missing object, network, or a link in the wrong state). */
public class S3StoreException extends RuntimeException {

    public S3StoreException(String message) {
        super(message);
    }

    /** The remote object changed (or already exists) since this model last synchronised with it. */
    public static class Conflict extends S3StoreException {

        private final String key;

        public Conflict(String key, String message) {
            super(message);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
