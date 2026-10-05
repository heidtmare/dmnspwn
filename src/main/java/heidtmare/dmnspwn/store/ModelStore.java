package heidtmare.dmnspwn.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Where models are kept, with their undo history, test scenarios and metadata. Writes of models and test files are
 * conditional on the version that was read, so that several application instances can share one store: a write
 * based on a version that has since changed fails with a {@link StoreConflictException} instead of losing the other
 * change.
 */
public interface ModelStore {

    Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,80}");

    /**
     * Identifies one stored version of a file. It changes whenever the content changes, also through writes made
     * elsewhere; S3 keeps it for a rewrite with the same content, which is harmless for conditional writes.
     */
    record Stamp(String tag, Instant modified) {
    }

    /**
     * A file's content with the version it was read at. {@code revision} counts the writes of a model and orders its
     * undo history; stores that do not need it leave it at 0.
     */
    record Stored(String content, Stamp stamp, long revision) {
    }

    record Entry(String id, Stamp stamp) {
    }

    /** A previous version of a model, kept for undo. */
    record Snapshot(String name, String content) {
    }

    /** Where the store keeps its files, for messages. */
    String location();

    /** All models, in id order. */
    List<Entry> list();

    Optional<Stored> read(String id);

    /** The current version of a model, or empty if it does not exist. */
    Optional<Stamp> stamp(String id);

    default boolean exists(String id) {
        return stamp(id).isPresent();
    }

    /**
     * Writes a model if it is still at {@code current}, or, when {@code current} is null, only if it does not exist
     * yet. With {@code snapshot} the content of {@code current} is kept for undo.
     *
     * @throws StoreConflictException when the model changed or exists
     */
    void write(String id, String xml, Stored current, boolean snapshot);

    /** Removes a model with its history, test scenarios and metadata. */
    void delete(String id);

    boolean hasHistory(String id);

    /** The most recent previous version of a model. */
    Optional<Snapshot> latestSnapshot(String id);

    void deleteSnapshot(String id, Snapshot snapshot);

    /** The model's test scenarios (DMN TCK test case XML), or empty if it has none. */
    Optional<Stored> readTests(String id);

    /**
     * Stores the model's test scenarios if they are still at {@code current} (null: only if there are none);
     * {@code xml == null} removes them.
     *
     * @throws StoreConflictException when the scenarios changed
     */
    void writeTests(String id, String xml, Stored current);

    /** Small per-model key/value metadata (e.g. the S3 link). Last write wins. */
    Properties readMeta(String id);

    /** The metadata of every model that has some, by model id. */
    Map<String, Properties> readAllMeta();

    void writeMeta(String id, Properties props);

    static boolean isValid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static String requireValidId(String id) {
        if (!isValid(id)) {
            throw new ModelNotFoundException(String.valueOf(id));
        }
        return id;
    }
}
