package heidtmare.dmnspwn.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import heidtmare.dmnspwn.edit.DmnEditor;
import heidtmare.dmnspwn.model.DmnReader;
import heidtmare.dmnspwn.model.ElementKind;
import heidtmare.dmnspwn.store.ModelStore.Entry;
import heidtmare.dmnspwn.store.ModelStore.Snapshot;
import heidtmare.dmnspwn.store.ModelStore.Stamp;
import heidtmare.dmnspwn.store.ModelStore.Stored;
import heidtmare.dmnspwn.xml.DmnDocument;
import heidtmare.dmnspwn.xml.DmnFormatException;

/**
 * Loads, edits and stores models. Every change reads the model, applies the edit and writes it back conditionally;
 * when another instance changed the model in between, the change is applied again to the new version.
 */
@Service
public class ModelService {

    static final int ATTEMPTS = 5;

    private final ModelStore store;
    /** Serializes changes of one model within this instance, so that they do not conflict with each other. */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** What the home page shows of each model, kept until the model changes so listing does not parse them all. */
    private final ConcurrentHashMap<String, Summary> summaries = new ConcurrentHashMap<>();

    private record Summary(Stamp stamp, String name, String version, String namespace,
                           int decisions, int elements, String error) {
    }

    public ModelService(ModelStore store) {
        this.store = store;
    }

    public List<ModelSummary> list() {
        List<Entry> entries = store.list();
        summaries.keySet().retainAll(entries.stream().map(Entry::id).toList());
        Map<String, Properties> meta = store.readAllMeta();
        List<ModelSummary> result = new ArrayList<>();
        for (Entry entry : entries) {
            String id = entry.id();
            Summary s = summaries.get(id);
            if (s == null || !s.stamp().equals(entry.stamp())) {
                Optional<Stored> stored = store.read(id);
                if (stored.isEmpty()) {
                    continue;
                }
                s = summarize(id, stored.get());
                summaries.put(id, s);
            }
            Properties props = meta.get(id);
            result.add(new ModelSummary(id, s.name(), s.version(), s.namespace(), s.decisions(), s.elements(),
                    s.stamp().modified(), s.error(), props == null ? null : props.getProperty("s3.key")));
        }
        result.sort(Comparator.comparing(ModelSummary::updated).reversed());
        return result;
    }

    private static Summary summarize(String id, Stored stored) {
        try {
            DmnReader reader = new DmnReader(DmnDocument.parse(stored.content()));
            var info = reader.info();
            long decisions = reader.nodeElements().values().stream()
                    .filter(e -> DmnReader.kindOf(e) == ElementKind.DECISION).count();
            return new Summary(stored.stamp(), info.name() == null ? id : info.name(), info.version(),
                    info.namespace(), (int) decisions, reader.nodeElements().size(), null);
        } catch (DmnFormatException e) {
            return new Summary(stored.stamp(), id, "?", null, 0, 0, e.getMessage());
        }
    }

    public String xml(String id) {
        return current(id).content();
    }

    /** The model's content and version. */
    public Stored current(String id) {
        return store.read(id).orElseThrow(() -> new ModelNotFoundException(id));
    }

    public DmnDocument load(String id) {
        return DmnDocument.parse(xml(id));
    }

    public DmnReader reader(String id) {
        return new DmnReader(load(id));
    }

    /** Applies an edit atomically: load, mutate, store (keeping the previous version for undo). */
    public <T> T update(String id, Function<DmnEditor, T> edit) {
        return atomically(id, () -> {
            Stored current = current(id);
            DmnDocument doc = DmnDocument.parse(current.content());
            T result = edit.apply(new DmnEditor(doc));
            store.write(id, doc.toXml(), current, true);
            return result;
        });
    }

    /** Applies an edit that has no result; see {@link #update}. */
    public void edit(String id, Consumer<DmnEditor> edit) {
        update(id, ed -> {
            edit.accept(ed);
            return null;
        });
    }

    /**
     * Runs a read-modify-write of the model's files. It holds the model's lock in this instance, and when a
     * conditional write fails because another instance changed a file, it runs {@code action} again (so it must
     * re-read what it changes). Calls may be nested; only the outermost one repeats.
     */
    public <T> T atomically(String id, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            if (lock.getHoldCount() > 1) {
                return action.get();
            }
            for (int attempt = 1; ; attempt++) {
                try {
                    return action.get();
                } catch (StoreConflictException e) {
                    if (attempt >= ATTEMPTS) {
                        throw new StoreConflictException(
                                "'" + id + "' is being changed by someone else at the same time; try again.");
                    }
                    pause(attempt);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreConflictException("Interrupted");
        }
    }

    public String create(String name) {
        String clean = name == null || name.isBlank() ? "New decision model" : name.strip();
        return createNew(clean, id -> DmnDocument.blank(clean, "https://dmnspwn.dev/dmn/" + id).toXml());
    }

    /** Imports an uploaded file after checking it is a DMN model; the original bytes are stored as-is. */
    public String importXml(String fileName, String xml) {
        DmnDocument doc = DmnDocument.parse(xml);
        String name = DmnDocument.attr(doc.definitions(), "name");
        String hint = name != null && !name.isBlank() ? name : fileName == null ? "model" : fileName.replaceFirst("\\.[^.]*$", "");
        return createNew(hint, id -> xml);
    }

    /** Stores a new model under a fresh id derived from {@code nameHint}; ids taken meanwhile are skipped. */
    private String createNew(String nameHint, Function<String, String> content) {
        String base = nameHint.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "model";
        }
        base = base.substring(0, Math.min(base.length(), 48));
        for (int attempt = 0; ; attempt++) {
            String id = attempt == 0 ? base : base + "-" + UUID.randomUUID().toString().substring(0, 6);
            try {
                store.write(id, content.apply(id), null, false);
                return id;
            } catch (StoreConflictException e) {
                if (attempt >= 20) {
                    throw e;
                }
            }
        }
    }

    /** Replaces the whole source after validating that it parses as DMN. */
    public void replaceSource(String id, String xml) {
        DmnDocument.parse(xml);
        atomically(id, () -> {
            store.write(id, xml, current(id), true);
            return null;
        });
    }

    public void delete(String id) {
        atomically(id, () -> {
            current(id);
            store.delete(id);
            return null;
        });
    }

    public boolean canUndo(String id) {
        return store.hasHistory(id);
    }

    /** Restores the most recent previous version. */
    public boolean undo(String id) {
        return atomically(id, () -> {
            Stored current = current(id);
            Optional<Snapshot> snapshot = store.latestSnapshot(id);
            if (snapshot.isEmpty()) {
                return false;
            }
            store.write(id, snapshot.get().content(), current, false);
            store.deleteSnapshot(id, snapshot.get());
            return true;
        });
    }
}
