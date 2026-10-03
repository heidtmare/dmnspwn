package heidtmare.dmnspwn.store;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Repository;

import heidtmare.dmnspwn.config.DmnProperties;

/** Stores each model as {@code <id>.dmn}, with previous versions under {@code .history/<id>/} for undo. */
@Repository
public class ModelRepository {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,80}");
    private static final String EXT = ".dmn";

    private final Path directory;
    private final Path history;
    private final Path meta;
    private final int historySize;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ModelRepository(DmnProperties properties) {
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

    public Path directory() {
        return directory;
    }

    public List<String> ids() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(EXT) && Files.isRegularFile(p))
                    .map(p -> p.getFileName().toString())
                    .map(n -> n.substring(0, n.length() - EXT.length()))
                    .filter(id -> ID.matcher(id).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String id) {
        return isValid(id) && Files.isRegularFile(file(id));
    }

    public Optional<String> read(String id) {
        if (!exists(id)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file(id), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Instant lastModified(String id) {
        try {
            return Files.getLastModifiedTime(file(id)).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /** Writes a model; when {@code snapshot} is set the previous content is kept for undo. */
    public void write(String id, String xml, boolean snapshot) {
        requireValid(id);
        Path target = file(id);
        try {
            if (snapshot && historySize > 0 && Files.exists(target)) {
                Path dir = history.resolve(id);
                Files.createDirectories(dir);
                Files.copy(target, dir.resolve(System.currentTimeMillis() + "-" + System.nanoTime() + EXT),
                        StandardCopyOption.REPLACE_EXISTING);
                prune(dir);
            }
            Path tmp = Files.createTempFile(directory, ".tmp-", EXT);
            Files.writeString(tmp, xml, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String id) {
        requireValid(id);
        try {
            Files.deleteIfExists(file(id));
            Files.deleteIfExists(meta.resolve(id + ".properties"));
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
    }

    public boolean hasHistory(String id) {
        return !snapshots(id).isEmpty();
    }

    /** Restores the most recent snapshot. */
    public boolean undo(String id) {
        List<Path> snapshots = snapshots(id);
        if (snapshots.isEmpty()) {
            return false;
        }
        Path latest = snapshots.getLast();
        try {
            write(id, Files.readString(latest, StandardCharsets.UTF_8), false);
            Files.delete(latest);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Small per-model key/value metadata (e.g. the S3 link), stored beside the model. */
    public Properties readMeta(String id) {
        Properties props = new Properties();
        Path file = meta.resolve(requireValidId(id) + ".properties");
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return props;
    }

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

    /** A fresh, filesystem-safe id derived from a model name. */
    public String newId(String nameHint) {
        String base = nameHint == null ? "" : nameHint.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "model";
        }
        base = base.substring(0, Math.min(base.length(), 48));
        String id = base;
        while (exists(id)) {
            id = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return id;
    }

    public ReentrantLock lock(String id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    public static boolean isValid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private void requireValid(String id) {
        requireValidId(id);
    }

    private static String requireValidId(String id) {
        if (!isValid(id)) {
            throw new ModelNotFoundException(String.valueOf(id));
        }
        return id;
    }

    private Path file(String id) {
        return directory.resolve(id + EXT);
    }

    private List<Path> snapshots(String id) {
        if (!isValid(id)) {
            return List.of();
        }
        Path dir = history.resolve(id);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(EXT))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), ModelRepository::compareSnapshots))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        List<Path> all;
        try (Stream<Path> files = Files.list(dir)) {
            all = files.sorted(Comparator.comparing(p -> p.getFileName().toString(), ModelRepository::compareSnapshots))
                    .toList();
        }
        for (int i = 0; i < all.size() - historySize; i++) {
            Files.deleteIfExists(all.get(i));
        }
    }
}
