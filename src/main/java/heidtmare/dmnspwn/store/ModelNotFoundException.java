package heidtmare.dmnspwn.store;

public class ModelNotFoundException extends RuntimeException {

    public ModelNotFoundException(String id) {
        super("Model '" + id + "' not found");
    }
}
