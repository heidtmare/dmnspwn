package heidtmare.dmnspwn.s3;

import static heidtmare.dmnspwn.store.ModelStore.requireValidId;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.StoreConflictException;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Keeps models in an S3 bucket under the storage prefix, so that any number of instances can share them:
 * <pre>
 *   &lt;prefix&gt;&lt;id&gt;.dmn                       the model (user metadata {@code revision})
 *   &lt;prefix&gt;&lt;id&gt;.tests.xml                 its test scenarios
 *   &lt;prefix&gt;.history/&lt;id&gt;/&lt;revision&gt;.dmn   previous versions, for undo
 *   &lt;prefix&gt;.meta/&lt;id&gt;.properties         metadata such as the S3 link
 * </pre>
 * Models and test files are written with S3 conditional writes ({@code If-Match} on the ETag that was read, or
 * {@code If-None-Match: *} for a new file). Each model write increments its revision, and the previous version is
 * kept under that revision's number, so the history is ordered without relying on clocks. Contents are cached by
 * ETag and revalidated with a conditional GET on every read.
 */
public class S3ModelStore implements ModelStore {

    private static final Logger log = LoggerFactory.getLogger(S3ModelStore.class);

    private static final String EXT = ".dmn";
    private static final String TESTS_EXT = ".tests.xml";
    private static final String REVISION = "revision";
    private static final Pattern SNAPSHOT = Pattern.compile("\\d{12}\\.dmn");

    private final S3Client s3;
    private final String bucket;
    private final String root;
    private final int historySize;
    private final long maxObjectSize;
    /** The last content read of each object, by key; revalidated with its ETag. */
    private final ConcurrentHashMap<String, Stored> cache = new ConcurrentHashMap<>();

    public S3ModelStore(S3Client s3, DmnProperties properties) {
        this.s3 = s3;
        this.bucket = properties.s3().bucket();
        this.root = properties.s3().storageRoot();
        this.historySize = Math.max(0, properties.historySize());
        this.maxObjectSize = properties.s3().maxObjectSize().toBytes();
    }

    @Override
    public String location() {
        return "s3://" + bucket + "/" + root;
    }

    @Override
    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        listing(root, true, o -> {
            String name = o.key().substring(root.length());
            if (name.endsWith(EXT)) {
                String id = name.substring(0, name.length() - EXT.length());
                if (ModelStore.isValid(id)) {
                    entries.add(new Entry(id, new Stamp(o.eTag(), o.lastModified())));
                }
            }
        });
        return entries;
    }

    @Override
    public Optional<Stored> read(String id) {
        return ModelStore.isValid(id) ? get(model(id)) : Optional.empty();
    }

    @Override
    public Optional<Stamp> stamp(String id) {
        if (!ModelStore.isValid(id)) {
            return Optional.empty();
        }
        String key = model(id);
        try {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key));
            return Optional.of(new Stamp(head.eTag(), head.lastModified()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw failed(e, "inspect", key);
        } catch (SdkException e) {
            throw failed(e, "inspect", key);
        }
    }

    @Override
    public void write(String id, String xml, Stored current, boolean snapshot) {
        requireValidId(id);
        long revision = current == null ? 0 : current.revision();
        List<String> history = List.of();
        boolean keep = snapshot && current != null && historySize > 0;
        if (keep) {
            // An object replaced outside the application has lost its revision; never number it below the history.
            history = snapshotKeys(id);
            if (!history.isEmpty()) {
                revision = Math.max(revision, number(history.getLast()) + 1);
            }
        }
        put(model(id), xml, current, Map.of(REVISION, Long.toString(revision + 1)), "Model '" + id + "'");
        if (keep) {
            String key = history(id) + "%012d".formatted(revision) + EXT;
            try {
                put(key, current.content(), null, Map.of(), null);
                List<String> all = new ArrayList<>(history);
                all.remove(key);
                all.add(key);
                for (int i = 0; i < all.size() - historySize; i++) {
                    remove(all.get(i));
                }
            } catch (RuntimeException e) {
                // The model is saved; only this undo step is lost.
                log.warn("Could not keep the previous version of {}: {}", id, e.getMessage());
            }
        }
    }

    @Override
    public void delete(String id) {
        requireValidId(id);
        remove(model(id));
        remove(tests(id));
        remove(meta(id));
        for (String key : snapshotKeys(id)) {
            remove(key);
        }
    }

    @Override
    public boolean hasHistory(String id) {
        return ModelStore.isValid(id) && !snapshotKeys(id).isEmpty();
    }

    @Override
    public Optional<Snapshot> latestSnapshot(String id) {
        if (!ModelStore.isValid(id)) {
            return Optional.empty();
        }
        List<String> keys = snapshotKeys(id);
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        String key = keys.getLast();
        Optional<Stored> stored = get(key);
        cache.remove(key);
        return stored.map(s -> new Snapshot(key.substring(key.lastIndexOf('/') + 1), s.content()));
    }

    @Override
    public void deleteSnapshot(String id, Snapshot snapshot) {
        if (!SNAPSHOT.matcher(snapshot.name()).matches()) {
            throw new IllegalArgumentException("Not a snapshot: " + snapshot.name());
        }
        remove(history(requireValidId(id)) + snapshot.name());
    }

    @Override
    public Optional<Stored> readTests(String id) {
        return get(tests(requireValidId(id)));
    }

    /** Removing the scenarios is not conditional: S3-compatible stores do not all support conditional deletes. */
    @Override
    public void writeTests(String id, String xml, Stored current) {
        String key = tests(requireValidId(id));
        if (xml == null) {
            if (current != null) {
                remove(key);
            }
            return;
        }
        put(key, xml, current, Map.of(), "The tests of '" + id + "'");
    }

    @Override
    public Properties readMeta(String id) {
        return get(meta(requireValidId(id))).map(s -> properties(s.content())).orElseGet(Properties::new);
    }

    @Override
    public Map<String, Properties> readAllMeta() {
        String dir = root + ".meta/";
        Map<String, Properties> result = new TreeMap<>();
        listing(dir, false, o -> {
            String name = o.key().substring(dir.length());
            String id = name.endsWith(".properties") ? name.substring(0, name.length() - 11) : "";
            if (!ModelStore.isValid(id)) {
                return;
            }
            Stored cached = cache.get(o.key());
            Optional<Stored> stored = cached != null && cached.stamp().tag().equals(o.eTag())
                    ? Optional.of(cached) : get(o.key());
            stored.ifPresent(s -> result.put(id, properties(s.content())));
        });
        return result;
    }

    @Override
    public void writeMeta(String id, Properties props) {
        String key = meta(requireValidId(id));
        if (props.isEmpty()) {
            remove(key);
            return;
        }
        StringWriter out = new StringWriter();
        try {
            props.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        put(key, out.toString(), null, Map.of(), null);
    }

    /** Reads an object, reusing the cached content when its ETag is unchanged. */
    private Optional<Stored> get(String key) {
        Stored cached = cache.get(key);
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (cached != null) {
            request.ifNoneMatch(cached.stamp().tag());
        }
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(request.build())) {
            GetObjectResponse response = in.response();
            if (response.contentLength() != null && response.contentLength() > maxObjectSize) {
                in.abort();
                throw new S3StoreException("%s is %d bytes; the limit is %d"
                        .formatted(location(key), response.contentLength(), maxObjectSize));
            }
            String content = new String(S3Bucket.readLimited(in, maxObjectSize, key), StandardCharsets.UTF_8);
            Stored stored = new Stored(content, new Stamp(response.eTag(), response.lastModified()),
                    revision(response.metadata()));
            cache.put(key, stored);
            return Optional.of(stored);
        } catch (S3Exception e) {
            if (e.statusCode() == 304 && cached != null) {
                return Optional.of(cached);
            }
            if (e.statusCode() == 404) {
                cache.remove(key);
                return Optional.empty();
            }
            throw failed(e, "read", key);
        } catch (SdkException e) {
            throw failed(e, "read", key);
        } catch (IOException e) {
            throw new S3StoreException("Reading " + location(key) + " failed: " + e.getMessage());
        }
    }

    /**
     * Uploads an object. With {@code what} the write is conditional: on {@code current}'s ETag, or for a null
     * {@code current} on the object not existing yet.
     */
    private void put(String key, String content, Stored current, Map<String, String> metadata, String what) {
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType("application/xml; charset=utf-8").metadata(metadata);
        if (what != null) {
            if (current != null) {
                request.ifMatch(current.stamp().tag());
            } else {
                request.ifNoneMatch("*");
            }
        }
        try {
            s3.putObject(request.build(), RequestBody.fromString(content, StandardCharsets.UTF_8));
        } catch (S3Exception e) {
            // 412: precondition failed, 409: a concurrent conditional write, 404: If-Match on a deleted object
            if (what != null && (e.statusCode() == 412 || e.statusCode() == 409 || e.statusCode() == 404)) {
                throw new StoreConflictException(current == null ? what + " already exists"
                        : what + " was changed by someone else");
            }
            throw failed(e, "write", key);
        } catch (SdkException e) {
            throw failed(e, "write", key);
        }
    }

    private void remove(String key) {
        cache.remove(key);
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw failed(e, "delete", key);
        }
    }

    /** The objects under {@code prefix}, in key order; with {@code shallow}, not those in sub-folders. */
    private void listing(String prefix, boolean shallow, Consumer<S3Object> each) {
        String token = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                    .continuationToken(token);
            if (shallow) {
                request.delimiter("/");
            }
            ListObjectsV2Response response;
            try {
                response = s3.listObjectsV2(request.build());
            } catch (SdkException e) {
                throw failed(e, "list", prefix);
            }
            response.contents().forEach(each);
            token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (token != null);
    }

    /** The model's snapshot keys, oldest first (the zero-padded revision numbers sort as text). */
    private List<String> snapshotKeys(String id) {
        String dir = history(id);
        List<String> keys = new ArrayList<>();
        listing(dir, true, o -> {
            if (SNAPSHOT.matcher(o.key().substring(dir.length())).matches()) {
                keys.add(o.key());
            }
        });
        keys.sort(null);
        return keys;
    }

    private static long number(String snapshotKey) {
        String name = snapshotKey.substring(snapshotKey.lastIndexOf('/') + 1);
        return Long.parseLong(name.substring(0, name.length() - EXT.length()));
    }

    private static long revision(Map<String, String> metadata) {
        String value = metadata == null ? null : metadata.get(REVISION);
        try {
            return value == null ? 0 : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Properties properties(String content) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }

    private S3StoreException failed(SdkException e, String action, String key) {
        if (e instanceof NoSuchKeyException) {
            return new S3StoreException("Could not " + action + " " + location(key) + ": the object does not exist");
        }
        return S3Bucket.translate(e, action + " " + location(key), bucket);
    }

    private String location(String key) {
        return "s3://" + bucket + "/" + key;
    }

    private String model(String id) {
        return root + id + EXT;
    }

    private String tests(String id) {
        return root + id + TESTS_EXT;
    }

    private String meta(String id) {
        return root + ".meta/" + id + ".properties";
    }

    private String history(String id) {
        return root + ".history/" + id + "/";
    }
}
