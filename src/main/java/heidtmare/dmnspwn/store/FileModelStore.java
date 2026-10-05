package heidtmare.dmnspwn.store;

import static heidtmare.dmnspwn.store.ModelStore.requireValidId;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import heidtmare.dmnspwn.config.DmnProperties;

/**
 * Stores each model as {@code <id>.dmn} in a directory, with previous versions under {@code .history/<id>/} for undo
 * and its test scenarios beside it as {@code <id>.tests.xml}. Conditional writes are checked within this process
 * only, so the directory must not be shared by several instances.
 */
public class FileModelStore implements ModelStore {

    private static final String EXT = ".dmn";
    private static final String TESTS_EXT = ".tests.xml";
    /** Snapshot file names: {@code <millis>-<nanos>.dmn}. Anything else in a history directory is ignored. */
    private static final Pattern SNAPSHOT = Pattern.compile("\\d+-\\d+\\.dmn");

    private final Path directory;
    private final Path history;
    private final Path meta;
    private final int historySize;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** Writes per file through this store, so that a stamp changes even when time and size do not. */
    private final ConcurrentHashMap<Path, Long> writes = new ConcurrentHashMap<>();

    public FileModelStore(DmnProperties properties) {
        this.directory = properties.storageDirectory().toAbsolutePath().normalize();
        this.history = directory.resolve(".history");
        this.meta = directory.resolve(".meta");
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
            return files.filter(p -> p.getFileName().toString().endsWith(EXT))
                    .map(p -> p.getFileName().toString())
                    .map(n -> n.substring(0, n.length() - EXT.length()))
                    .filter(ModelStore::isValid)
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
            try {
                if (snapshot && current != null && historySize > 0) {
                    Path dir = history.resolve(id);
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(System.currentTimeMillis() + "-" + System.nanoTime() + EXT),
                            current.content(), StandardCharsets.UTF_8);
                    prune(dir);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
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
                Files.deleteIfExists(file(id));
                writes.merge(file(id), 1L, Long::sum);
                Files.deleteIfExists(meta.resolve(id + ".properties"));
                Files.deleteIfExists(tests(id));
                writes.merge(tests(id), 1L, Long::sum);
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
        if (!SNAPSHOT.matcher(snapshot.name()).matches()) {
            throw new IllegalArgumentException("Not a snapshot: " + snapshot.name());
        }
        try {
            Files.deleteIfExists(history.resolve(requireValidId(id)).resolve(snapshot.name()));
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
                    Files.deleteIfExists(file);
                    writes.merge(file, 1L, Long::sum);
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
        return readProperties(meta.resolve(requireValidId(id) + ".properties"));
    }

    @Override
    public Map<String, Properties> readAllMeta() {
        Map<String, Properties> result = new TreeMap<>();
        try (Stream<Path> files = Files.list(meta)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                String id = name.endsWith(".properties") ? name.substring(0, name.length() - 11) : "";
                if (ModelStore.isValid(id)) {
                    result.put(id, readProperties(p));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @Override
    public void writeMeta(String id, Properties props) {
        Path file = meta.resolve(requireValidId(id) + ".properties");
        try {
            if (props.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(out, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T locked(String id, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Fails unless {@code file} is still at {@code current} (or, for null, does not exist). */
    private void check(Path file, Stored current, String what) {
        Optional<Stamp> actual = stampOf(file);
        if (current == null ? actual.isPresent() : !actual.equals(Optional.of(current.stamp()))) {
            throw new StoreConflictException(current == null ? what + " already exists"
                    : what + " was changed by someone else");
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
            Path tmp = Files.createTempFile(directory, ".tmp-", EXT);
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

    private static Properties readProperties(Path file) {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return props;
    }

    private Path file(String id) {
        return directory.resolve(id + EXT);
    }

    private Path tests(String id) {
        return directory.resolve(id + TESTS_EXT);
    }

    private List<Path> snapshots(String id) {
        if (!ModelStore.isValid(id)) {
            return List.of();
        }
        Path dir = history.resolve(id);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try {
            return snapshotsIn(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The snapshot files in a history directory, oldest first. */
    private static List<Path> snapshotsIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> SNAPSHOT.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), FileModelStore::compareSnapshots))
                    .toList();
        }
    }

    private static int compareSnapshots(String a, String b) {
        String[] pa = a.replace(EXT, "").split("-");
        String[] pb = b.replace(EXT, "").split("-");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int c = Long.compare(Long.parseLong(pa[i]), Long.parseLong(pb[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private void prune(Path dir) throws IOException {
        List<Path> all = snapshotsIn(dir);
        for (int i = 0; i < all.size() - historySize; i++) {
            Files.deleteIfExists(all.get(i));
        }
    }
}
