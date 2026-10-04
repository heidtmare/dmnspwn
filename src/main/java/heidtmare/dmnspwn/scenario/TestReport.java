package heidtmare.dmnspwn.scenario;

import java.util.List;

import heidtmare.dmnspwn.eval.Trace;

/** The outcome of running a model's scenarios; {@code error} is set when the test file could not be read. */
public record TestReport(List<ScenarioResult> results, String error) {

    public static final TestReport EMPTY = new TestReport(List.of(), null);

    /** One expected decision result compared with the actual one. */
    public record Check(String decision, String decisionId, String expected, String actual, boolean passed,
                        List<Trace.Message> messages, List<Integer> matchedRules, boolean table) {
    }

    /** One scenario; {@code errors} are problems that stop it from being checked, such as unknown inputs. */
    public record ScenarioResult(int index, Scenario scenario, List<String> errors, List<Check> checks) {
        public boolean passed() {
            return errors.isEmpty() && !checks.isEmpty() && checks.stream().allMatch(Check::passed);
        }
    }

    public int total() {
        return results.size();
    }

    public long failed() {
        return results.stream().filter(r -> !r.passed()).count();
    }

    public long passed() {
        return total() - failed();
    }
}
