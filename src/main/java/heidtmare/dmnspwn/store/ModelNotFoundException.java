package heidtmare.dmnspwn.store;

public class ModelNotFoundException extends NotFoundException {

    public ModelNotFoundException(String id) {
        super("Model '" + id + "' not found");
    }
}
