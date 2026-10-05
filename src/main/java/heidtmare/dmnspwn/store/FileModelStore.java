package heidtmare.dmnspwn.store;

import static heidtmare.dmnspwn.store.ModelStore.requireValidId;
import static heidtmare.dmnspwn.store.StoreLayout.HISTORY_DIR;
import static heidtmare.dmnspwn.store.StoreLayout.META_DIR;
import static heidtmare.dmnspwn.store.StoreLayout.META_EXT;
import static heidtmare.dmnspwn.store.StoreLayout.MODEL_EXT;
import static heidtmare.dmnspwn.store.StoreLayout.TESTS_EXT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import heidtmare.dmnspwn.config.DmnProperties;

/**
 * Stores models in a directory, laid out as described in {@link StoreLayout}. Conditional writes are checked within
 * this process only, so the directory must not be shared by several instances.
 */
public class FileModelStore implements ModelStore {

    /** Snapshot names written by earlier versions: {@code <millis>-<nanos>.dmn}; renumbered when first listed. */
    private static final Pattern LEGACY_SNAPSHOT = Pattern.compile("(\\d+)-(\\d+)\\.dmn");

    private final Path directory;
    private final Path history;
    private final Path meta;
    private final int historySize;
    private final KeyedLocks locks = new KeyedLocks();
    /** Writes per file through this store, so that a stamp changes even when time and size do not. */
    private final ConcurrentHashMap<Path, Long> writes = new ConcurrentHashMap<>();

    public FileModelStore(DmnProperties properties) {
        this.directory = properties.storageDirectory().toAbsolutePath().normalize();
        this.history = directory.resolve(HISTORY_DIR);
        this.meta = directory.resolve(META_DIR);
        this.historySize = Math.max(0, properties.historySize());
        try {
            Files.createDirectories(history);
            Files.createDirectories(meta);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + directory, e);
        }
    }

    @Override
    public String location() {
        return directory.toString();
    }

    @Override
    public List<Entry> list() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.flatMap(p -> StoreLayout.idOf(p.getFileName().toString(), MODEL_EXT).stream())
                    .sorted()
                    .flatMap(id -> stampOf(file(id)).map(s -> new Entry(id, s)).stream())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Stored> read(String id) {
        return ModelStore.isValid(id) ? locked(id, () -> readFile(file(id))) : Optional.empty();
    }

    @Override
    public Optional<Stamp> stamp(String id) {
        return ModelStore.isValid(id) ? stampOf(file(id)) : Optional.empty();
    }

    @Override
    public void write(String id, String xml, Stored current, boolean snapshot) {
        requireValidId(id);
        locked(id, () -> {
            Path target = file(id);
            check(target, current, "Model '" + id + "'");
            if (snapshot && current != null && historySize > 0) {
                keep(id, current.content());
            }
            replace(target, xml);
            return null;
        });
    }

    @Override
    public void delete(String id) {
        requireValidId(id);
        locked(id, () -> {
            try {
                deleteFile(file(id));
                deleteFile(tests(id));
                Files.deleteIfExists(metaFile(id));
                Path dir = history.resolve(id);
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> files = Files.list(dir)) {
                        for (Path p : files.toList()) {
                            Files.deleteIfExists(p);
                        }
                    }
                    Files.deleteIfExists(dir);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        });
    }

    @Override
    public boolean hasHistory(String id) {
        return !snapshots(id).isEmpty();
    }

    @Override
    public Optional<Snapshot> latestSnapshot(String id) {
        List<Path> snapshots = snapshots(id);
        if (snapshots.isEmpty()) {
            return Optional.empty();
        }
        Path latest = snapshots.getLast();
        try {
            return Optional.of(new Snapshot(latest.getFileName().toString(),
                    Files.readString(latest, StandardCharsets.UTF_8)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteSnapshot(String id, Snapshot snapshot) {
        String name = StoreLayout.requireSnapshot(snapshot.name());
        try {
            Files.deleteIfExists(history.resolve(requireValidId(id)).resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Stored> readTests(String id) {
        requireValidId(id);
        return locked(id, () -> readFile(tests(id)));
    }

    @Override
    public void writeTests(String id, String xml, Stored current) {
        requireValidId(id);
        locked(id, () -> {
            Path file = tests(id);
            check(file, current, "The tests of '" + id + "'");
            if (xml == null) {
                try {
                    deleteFile(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                replace(file, xml);
            }
            return null;
        });
    }

    @Override
    public Properties readMeta(String id) {
        return readProperties(metaFile(requireValidId(id)));
    }

    @Override
    public Map<String, Properties> readAllMeta() {
        Map<String, Properties> result = new TreeMap<>();
        try (Stream<Path> files = Files.list(meta)) {
            for (Path p : files.toList()) {
                StoreLayout.idOf(p.getFileName().toString(), META_EXT).ifPresent(id -> result.put(id, readProperties(p)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @Override
    public void writeMeta(String id, Properties props) {
        Path file = metaFile(requireValidId(id));
        try {
            if (props.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, StoreLayout.encode(props), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T locked(String id, Supplier<T> action) {
        return locks.locked(id, action);
    }

    /** Fails unless {@code file} is still at {@code current} (or, for null, does not exist). */
    private void check(Path file, Stored current, String what) {
        Optional<Stamp> actual = stampOf(file);
        if (current == null ? actual.isPresent() : !actual.equals(Optional.of(current.stamp()))) {
            throw StoreLayout.conflict(what, current);
        }
    }

    /** Keeps {@code content} as the newest snapshot, numbered after the previous one, and prunes the oldest. */
    private void keep(String id, String content) {
        try {
            Path dir = history.resolve(id);
            Files.createDirectories(dir);
            List<Path> existing = snapshotsIn(dir);
            long revision = existing.isEmpty() ? 1 : StoreLayout.revisionOf(existing.getLast().getFileName().toString()) + 1;
            Files.writeString(dir.resolve(StoreLayout.snapshotName(revision)), content, StandardCharsets.UTF_8);
            for (int i = 0; i < existing.size() + 1 - historySize; i++) {
                Files.deleteIfExists(existing.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Stored> readFile(Path file) {
        Optional<Stamp> stamp = stampOf(file);
        if (stamp.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Stored(Files.readString(file, StandardCharsets.UTF_8), stamp.get(), 0));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file's time and size, and the number of writes through this store; changes made elsewhere show too. */
    private Optional<Stamp> stampOf(Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            if (!a.isRegularFile()) {
                return Optional.empty();
            }
            String tag = a.lastModifiedTime().toInstant() + "/" + a.size() + "/" + writes.getOrDefault(file, 0L);
            return Optional.of(new Stamp(tag, a.lastModifiedTime().toInstant()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void replace(Path target, String content) {
        try {
            Path tmp = Files.createTempFile(directory, ".tmp-", MODEL_EXT);
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            writes.merge(target, 1L, Long::sum);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteFile(Path file) throws IOException {
        Files.deleteIfExists(file);
        writes.merge(file, 1L, Long::sum);
    }

    private static Properties readProperties(Path file) {
        if (!Files.isRegularFile(file)) {
            return new Properties();
        }
        try {
            return StoreLayout.decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String id) {
        return directory.resolve(id + MODEL_EXT);
    }

    private Path tests(String id) {
        return directory.resolve(id + TESTS_EXT);
    }

    private Path metaFile(String id) {
        return meta.resolve(id + META_EXT);
    }

    private List<Path> snapshots(String id) {
        if (!ModelStore.isValid(id)) {
            return List.of();
        }
        Path dir = history.resolve(id);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        return locked(id, () -> {
            try {
                return snapshotsIn(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The snapshot files in a history directory, oldest first (their names sort as text). */
    private static List<Path> snapshotsIn(Path dir) throws IOException {
        migrateLegacySnapshots(dir);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> StoreLayout.isSnapshot(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Renames snapshots written by earlier versions ({@code <millis>-<nanos>.dmn}) to revision numbers. They are
     * older than any numbered snapshot, so all snapshots are renumbered from 1 in order: legacy ones first.
     */
    private static void migrateLegacySnapshots(Path dir) throws IOException {
        List<Path> legacy;
        List<Path> numbered;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.toList();
            legacy = all.stream().filter(p -> LEGACY_SNAPSHOT.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> legacyPart(p, 1)).thenComparing(p -> legacyPart(p, 2)))
                    .toList();
            if (legacy.isEmpty()) {
                return;
            }
            numbered = all.stream().filter(p -> StoreLayout.isSnapshot(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        List<Path> ordered = Stream.concat(legacy.stream(), numbered.stream()).toList();
        // Two steps, so that no rename replaces a snapshot that is still to be renamed.
        Path[] staged = new Path[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            staged[i] = Files.move(ordered.get(i), dir.resolve(".migrating-" + i));
        }
        for (int i = 0; i < staged.length; i++) {
            Files.move(staged[i], dir.resolve(StoreLayout.snapshotName(i + 1)));
        }
    }

    private static long legacyPart(Path p, int group) {
        var m = LEGACY_SNAPSHOT.matcher(p.getFileName().toString());
        return m.matches() ? Long.parseLong(m.group(group)) : 0;
    }
}
