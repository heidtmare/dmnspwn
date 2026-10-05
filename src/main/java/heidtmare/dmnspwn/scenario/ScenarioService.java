package heidtmare.dmnspwn.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.scenario.ScenarioRunner.TestReport.Check;
import heidtmare.dmnspwn.scenario.ScenarioRunner.TestReport.ScenarioResult;
import heidtmare.dmnspwn.store.LruCache;
import heidtmare.dmnspwn.store.NotFoundException;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.xml.DmnFormatException;
import heidtmare.dmnspwn.scenario.ScenarioRunner.TestReport;

/**
 * Stores each model's test scenarios beside it (see {@link ModelStore#readTests}) and runs them. Reports are
 * kept until the model or its scenarios change, so every page can show the test status cheaply.
 */
@Service
public class ScenarioService {

    /** Models whose test report is kept; the least recently viewed are run again when needed. */
    private static final int CACHED_REPORTS = 200;

    private final ModelService models;
    private final ModelStore store;
    private final Feel feel;
    private final LruCache<String, Cached> reports = new LruCache<>(CACHED_REPORTS);

    private record Cached(ModelStore.Stamp stamp, String xml, TestReport report) {
    }

    public ScenarioService(ModelService models, ModelStore store, Feel feel) {
        this.models = models;
        this.store = store;
        this.feel = feel;
    }

    public List<Scenario> list(String id) {
        return tests(id).map(TestCases::read).orElse(List.of());
    }

    /** The current results of the model's scenarios; an unreadable test file is reported, not thrown. */
    public TestReport report(String id) {
        ModelStore.Stamp stamp = store.stamp(id).orElseThrow(() -> NotFoundException.model(id));
        Optional<String> xml = tests(id);
        if (xml.isEmpty()) {
            reports.remove(id);
            return TestReport.EMPTY;
        }
        Cached cached = reports.get(id);
        if (cached != null && cached.stamp().equals(stamp) && cached.xml().equals(xml.get())) {
            return cached.report();
        }
        TestReport report;
        try {
            report = new TestReport(runner(id).runAll(TestCases.read(xml.get())), null);
        } catch (DmnFormatException e) {
            report = new TestReport(List.of(), "The saved tests cannot be read: " + e.getMessage());
        }
        reports.put(id, new Cached(stamp, xml.get(), report));
        return report;
    }

    /** A runner for the model's current version. */
    private ScenarioRunner runner(String id) {
        return runner(new ModelEvaluator(models.reader(id), feel));
    }

    /** A runner using an evaluator the caller also uses for other things. */
    public ScenarioRunner runner(ModelEvaluator evaluator) {
        return new ScenarioRunner(evaluator, feel);
    }

    /** Adds a scenario, replacing one with the same name; returns whether one was replaced. */
    public boolean save(String id, Scenario scenario) {
        return change(id, list -> {
            Scenario s = scenario.name().isEmpty()
                    ? new Scenario(nextName(list), scenario.inputs(), scenario.expected()) : scenario;
            return merge(list, List.of(s)) > 0;
        });
    }

    /** Adds the scenarios of a TCK file, replacing those with the same names; returns how many were read. */
    public int importXml(String id, String xml) {
        List<Scenario> imported = TestCases.read(xml);
        if (imported.isEmpty()) {
            throw new InvalidScenarioException("The file contains no decision test cases");
        }
        change(id, list -> merge(list, imported));
        return imported.size();
    }

    public void delete(String id, int index) {
        change(id, list -> list.remove(checkIndex(list, index)));
    }

    /** Makes the current results of a scenario its expected results. */
    public Scenario accept(String id, int index) {
        return change(id, list -> {
            Scenario s = list.get(checkIndex(list, index));
            ScenarioResult result = runner(id).run(index, s);
            Map<String, String> expected = new LinkedHashMap<>();
            for (Check c : result.checks()) {
                expected.put(c.decision(), c.decisionId() == null ? c.expected() : c.actual());
            }
            Scenario accepted = s.withExpected(expected);
            list.set(index, accepted);
            return accepted;
        });
    }

    public Scenario get(String id, int index) {
        List<Scenario> list = list(id);
        return list.get(checkIndex(list, index));
    }

    /** The model's scenarios as a TCK file. */
    public String xml(String id) {
        models.requireExists(id);
        return tests(id).orElseGet(() -> TestCases.write(id + ".dmn", List.of(), feel));
    }

    private Optional<String> tests(String id) {
        return store.readTests(id).map(ModelStore.Stored::content);
    }

    /**
     * Edits the model's scenario list in place and stores it (see {@link ModelService#atomically}); returns what
     * {@code edit} returns.
     */
    private <T> T change(String id, Function<List<Scenario>, T> edit) {
        return models.atomically(id, () -> {
            models.requireExists(id);
            ModelStore.Stored current = store.readTests(id).orElse(null);
            List<Scenario> list = current == null ? new ArrayList<>() : new ArrayList<>(TestCases.read(current.content()));
            T result = edit.apply(list);
            store.writeTests(id, list.isEmpty() ? null : TestCases.write(id + ".dmn", list, feel), current);
            return result;
        });
    }

    /** Adds scenarios to {@code list}, replacing those with the same names; returns how many were replaced. */
    private static int merge(List<Scenario> list, List<Scenario> added) {
        int replaced = 0;
        for (Scenario s : added) {
            int existing = -1;
            for (int i = 0; i < list.size() && existing < 0; i++) {
                if (list.get(i).name().equals(s.name())) {
                    existing = i;
                }
            }
            if (existing >= 0) {
                list.set(existing, s);
                replaced++;
            } else {
                list.add(s);
            }
        }
        return replaced;
    }

    private static String nextName(List<Scenario> list) {
        for (int n = list.size() + 1; ; n++) {
            String name = "Test " + n;
            if (list.stream().noneMatch(s -> s.name().equals(name))) {
                return name;
            }
        }
    }

    private static int checkIndex(List<Scenario> list, int index) {
        if (index < 0 || index >= list.size()) {
            throw new InvalidScenarioException("The test no longer exists");
        }
        return index;
    }
}
