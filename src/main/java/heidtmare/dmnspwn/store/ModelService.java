package heidtmare.dmnspwn.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

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

    public ModelService(ModelRepository repository) {
        this.repository = repository;
    }

    public List<ModelSummary> list() {
        List<ModelSummary> result = new ArrayList<>();
        for (String id : repository.ids()) {
            try {
                DmnReader reader = new DmnReader(DmnDocument.parse(repository.read(id).orElse("")));
                var info = reader.info();
                long decisions = reader.nodeElements().values().stream()
                        .filter(e -> DmnReader.kindOf(e) == ElementKind.DECISION).count();
                result.add(new ModelSummary(id, info.name() == null ? id : info.name(), info.version(),
                        info.namespace(), (int) decisions, reader.nodeElements().size(),
                        repository.lastModified(id), null, repository.readMeta(id).getProperty("s3.key")));
            } catch (DmnFormatException e) {
                result.add(new ModelSummary(id, id, "?", null, 0, 0, repository.lastModified(id), e.getMessage(),
                        repository.readMeta(id).getProperty("s3.key")));
            }
        }
        result.sort(Comparator.comparing(ModelSummary::updated).reversed());
        return result;
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
        ReentrantLock lock = repository.lock(id);
        lock.lock();
        try {
            DmnDocument doc = load(id);
            T result = edit.apply(new DmnEditor(doc));
            repository.write(id, doc.toXml(), true);
            return result;
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
        ReentrantLock lock = repository.lock(id);
        lock.lock();
        try {
            xml(id);
            repository.write(id, xml, true);
        } finally {
            lock.unlock();
        }
    }

    public void delete(String id) {
        xml(id);
        repository.delete(id);
    }

    public boolean canUndo(String id) {
        return repository.hasHistory(id);
    }

    public boolean undo(String id) {
        ReentrantLock lock = repository.lock(id);
        lock.lock();
        try {
            return repository.undo(id);
        } finally {
            lock.unlock();
        }
    }
}
