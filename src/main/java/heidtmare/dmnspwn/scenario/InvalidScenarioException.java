package heidtmare.dmnspwn.scenario;

/** A test scenario, or a change of one, is not valid (shown to the user as a message). */
public class InvalidScenarioException extends RuntimeException {

    public InvalidScenarioException(String message) {
        super(message);
    }
}
