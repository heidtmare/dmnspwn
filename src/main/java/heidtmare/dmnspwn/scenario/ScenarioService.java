package heidtmare.dmnspwn.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Service;

import heidtmare.dmnspwn.edit.DmnEditException;
import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.ModelEvaluator;
import heidtmare.dmnspwn.scenario.TestReport.Check;
import heidtmare.dmnspwn.scenario.TestReport.ScenarioResult;
import heidtmare.dmnspwn.store.ModelNotFoundException;
import heidtmare.dmnspwn.store.ModelRepository;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.xml.DmnFormatException;

/**
 * Stores each model's test scenarios beside it (see {@link ModelRepository#readTests}) and runs them. Reports are
 * kept until the model or its scenarios change, so every page can show the test status cheaply.
 */
@Service
public class ScenarioService {

    private final ModelService models;
    private final ModelRepository repository;
    private final Feel feel;
    private final ConcurrentHashMap<String, Cached> reports = new ConcurrentHashMap<>();

    private record Cached(ModelRepository.Stamp stamp, String xml, TestReport report) {
    }

    public ScenarioService(ModelService models, ModelRepository repository, Feel feel) {
        this.models = models;
        this.repository = repository;
        this.feel = feel;
    }

    public List<Scenario> list(String id) {
        return repository.readTests(id).map(TestCases::read).orElse(List.of());
    }

    /** The current results of the model's scenarios; an unreadable test file is reported, not thrown. */
    public TestReport report(String id) {
        ModelRepository.Stamp stamp = repository.stamp(id).orElseThrow(() -> new ModelNotFoundException(id));
        Optional<String> xml = repository.readTests(id);
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
            report = new TestReport(List.of(), "The test file " + id + ".tests.xml cannot be read: " + e.getMessage());
        }
        reports.put(id, new Cached(stamp, xml.get(), report));
        return report;
    }

    public ScenarioRunner runner(String id) {
        return new ScenarioRunner(new ModelEvaluator(models.reader(id), feel), feel);
    }

    /** Adds a scenario, replacing one with the same name; returns whether one was replaced. */
    public boolean save(String id, Scenario scenario) {
        return change(id, list -> {
            Scenario s = scenario.name().isEmpty()
                    ? new Scenario(nextName(list), scenario.inputs(), scenario.expected()) : scenario;
            return merge(list, List.of(s));
        }) == 0;
    }

    /** Adds the scenarios of a TCK file, replacing those with the same names; returns how many were read. */
    public int importXml(String id, String xml) {
        List<Scenario> imported = TestCases.read(xml);
        if (imported.isEmpty()) {
            throw new DmnEditException("The file contains no decision test cases");
        }
        change(id, list -> merge(list, imported));
        return imported.size();
    }

    public void delete(String id, int index) {
        change(id, list -> {
            list.remove(checkIndex(list, index));
            return list;
        });
    }

    /** Makes the current results of a scenario its expected results. */
    public Scenario accept(String id, int index) {
        Scenario[] accepted = new Scenario[1];
        change(id, list -> {
            Scenario s = list.get(checkIndex(list, index));
            ScenarioResult result = runner(id).run(index, s);
            Map<String, String> expected = new LinkedHashMap<>();
            for (Check c : result.checks()) {
                expected.put(c.decision(), c.decisionId() == null ? c.expected() : c.actual());
            }
            accepted[0] = s.withExpected(expected);
            list.set(index, accepted[0]);
            return list;
        });
        return accepted[0];
    }

    public Scenario get(String id, int index) {
        List<Scenario> list = list(id);
        return list.get(checkIndex(list, index));
    }

    /** The model's scenarios as a TCK file. */
    public String xml(String id) {
        models.xml(id);
        return repository.readTests(id).orElseGet(() -> TestCases.write(id + ".dmn", List.of(), feel));
    }

    /** Edits the scenario list under the model's lock; returns the size difference. */
    private int change(String id, UnaryOperator<List<Scenario>> edit) {
        return models.withLock(id, () -> {
            models.xml(id);
            List<Scenario> before = list(id);
            List<Scenario> after = edit.apply(new ArrayList<>(before));
            repository.writeTests(id, after.isEmpty() ? null : TestCases.write(id + ".dmn", after, feel));
            return after.size() - before.size();
        });
    }

    private static List<Scenario> merge(List<Scenario> list, List<Scenario> added) {
        for (Scenario s : added) {
            int existing = -1;
            for (int i = 0; i < list.size() && existing < 0; i++) {
                if (list.get(i).name().equals(s.name())) {
                    existing = i;
                }
            }
            if (existing >= 0) {
                list.set(existing, s);
            } else {
                list.add(s);
            }
        }
        return list;
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
            throw new DmnEditException("The test no longer exists");
        }
        return index;
    }
}
