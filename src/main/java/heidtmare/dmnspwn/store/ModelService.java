package heidtmare.dmnspwn.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import heidtmare.dmnspwn.edit.DmnEditor;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnFormatException;

/** Loads, edits and stores models. Edits on one model are serialized by a per-model lock. */
@Service
public class ModelService {

    private final ModelRepository repository;
    /** What the home page shows of each model, kept until the model changes so listing does not parse them all. */
    private final ConcurrentHashMap<String, Summary> summaries = new ConcurrentHashMap<>();

    private record Summary(ModelRepository.Stamp stamp, String name, String version, String namespace,
                           int decisions, int elements, String error) {
    }

    public ModelService(ModelRepository repository) {
        this.repository = repository;
    }

    public List<ModelSummary> list() {
        List<String> ids = repository.ids();
        summaries.keySet().retainAll(ids);
        List<ModelSummary> result = new ArrayList<>();
        for (String id : ids) {
            Optional<ModelRepository.Stamp> stamp = repository.stamp(id);
            if (stamp.isEmpty()) {
                continue;
            }
            Summary s = summaries.get(id);
            if (s == null || !s.stamp().equals(stamp.get())) {
                s = summarize(id, stamp.get());
                summaries.put(id, s);
            }
            result.add(new ModelSummary(id, s.name(), s.version(), s.namespace(), s.decisions(), s.elements(),
                    stamp.get().modified(), s.error(), repository.readMeta(id).getProperty("s3.key")));
        }
        result.sort(Comparator.comparing(ModelSummary::updated).reversed());
        return result;
    }

    /** Parses one model for the listing; {@code stamp} was taken before reading, so it is never newer. */
    private Summary summarize(String id, ModelRepository.Stamp stamp) {
        try {
            DmnReader reader = new DmnReader(DmnDocument.parse(repository.read(id).orElse("")));
            var info = reader.info();
            long decisions = reader.nodeElements().values().stream()
                    .filter(e -> DmnReader.kindOf(e) == ElementKind.DECISION).count();
            return new Summary(stamp, info.name() == null ? id : info.name(), info.version(), info.namespace(),
                    (int) decisions, reader.nodeElements().size(), null);
        } catch (DmnFormatException e) {
            return new Summary(stamp, id, "?", null, 0, 0, e.getMessage());
        }
    }

    public String xml(String id) {
        return repository.read(id).orElseThrow(() -> new ModelNotFoundException(id));
    }

    public DmnDocument load(String id) {
        return DmnDocument.parse(xml(id));
    }

    public DmnReader reader(String id) {
        return new DmnReader(load(id));
    }

    /** Applies an edit atomically: load, mutate, store (keeping the previous version for undo). */
    public <T> T update(String id, Function<DmnEditor, T> edit) {
        return withLock(id, () -> {
            DmnDocument doc = load(id);
            T result = edit.apply(new DmnEditor(doc));
            repository.write(id, doc.toXml(), true);
            return result;
        });
    }

    /**
     * Runs {@code action} while holding the model's lock, so that no edit, undo or delete of the model
     * interleaves with it. The lock is reentrant: the service's own methods may be called from {@code action}.
     */
    public <T> T withLock(String id, Supplier<T> action) {
        ReentrantLock lock = repository.lock(id);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public String create(String name) {
        String clean = name == null || name.isBlank() ? "New decision model" : name.strip();
        String id = repository.newId(clean);
        DmnDocument doc = DmnDocument.blank(clean, "https://dmnspwn.dev/dmn/" + id);
        repository.write(id, doc.toXml(), false);
        return id;
    }

    /** Imports an uploaded file after checking it is a DMN model; the original bytes are stored as-is. */
    public String importXml(String fileName, String xml) {
        DmnDocument doc = DmnDocument.parse(xml);
        String name = DmnDocument.attr(doc.definitions(), "name");
        String hint = name != null && !name.isBlank() ? name : fileName == null ? "model" : fileName.replaceFirst("\\.[^.]*$", "");
        String id = repository.newId(hint);
        repository.write(id, xml, false);
        return id;
    }

    /** Replaces the whole source after validating that it parses as DMN. */
    public void replaceSource(String id, String xml) {
        DmnDocument.parse(xml);
        withLock(id, () -> {
            xml(id);
            repository.write(id, xml, true);
            return null;
        });
    }

    public void delete(String id) {
        withLock(id, () -> {
            xml(id);
            repository.delete(id);
            return null;
        });
    }

    public boolean canUndo(String id) {
        return repository.hasHistory(id);
    }

    public boolean undo(String id) {
        return withLock(id, () -> repository.undo(id));
    }
}
