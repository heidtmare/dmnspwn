package heidtmare.dmnspwn.s3;

import heidtmare.dmnspwn.edit.DmnEditException;

/** A user-visible S3 failure (access denied, missing object, network...). */
public class S3StoreException extends DmnEditException {

    public S3StoreException(String message) {
        super(message);
    }
}
