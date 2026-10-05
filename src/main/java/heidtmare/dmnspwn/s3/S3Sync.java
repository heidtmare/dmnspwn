package heidtmare.dmnspwn.s3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.springframework.stereotype.Service;

import heidtmare.dmnspwn.s3.S3Bucket.RemoteObject;
import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.ModelService;
import heidtmare.dmnspwn.xml.DmnDocument;

/**
 * Loads models from S3 and publishes them back. Each local model remembers the key it is linked to,
 * the object's ETag and a hash of the content at the last sync, so that:
 * <ul>
 *   <li>publishing uses a conditional write and never silently overwrites a remote change,</li>
 *   <li>reloading never silently discards unpublished local edits.</li>
 * </ul>
 */
@Service
@ConditionalOnS3Enabled
public class S3Sync {

    static final String KEY = "s3.key";
    static final String ETAG = "s3.etag";
    static final String HASH = "s3.hash";
    static final String SYNCED = "s3.syncedAt";

    public record Link(String key, String etag, String hash, Instant syncedAt) {
        public String syncedText() {
            return syncedAt == null ? "" : S3Bucket.TIME.format(syncedAt);
        }
    }

    public enum Remote { IN_SYNC, CHANGED, MISSING, UNKNOWN }

    public record Status(Link link, boolean localChanged, Remote remote, String remoteError) {
    }

    private final S3Bucket bucket;
    private final ModelService models;
    private final ModelStore store;

    public S3Sync(S3Bucket bucket, ModelService models, ModelStore store) {
        this.bucket = bucket;
        this.models = models;
        this.store = store;
    }

    public S3Bucket bucket() {
        return bucket;
    }

    public Optional<Link> link(String id) {
        return link(store.readMeta(id));
    }

    private static Optional<Link> link(Properties p) {
        String key = p.getProperty(KEY);
        if (key == null) {
            return Optional.empty();
        }
        String synced = p.getProperty(SYNCED);
        return Optional.of(new Link(key, p.getProperty(ETAG), p.getProperty(HASH),
                synced == null ? null : Instant.parse(synced)));
    }

    /** Local model ids by linked S3 key. */
    public Map<String, String> linkedModels() {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, Properties> meta = store.readAllMeta();
        for (ModelStore.Entry entry : store.list()) {
            Properties p = meta.get(entry.id());
            if (p != null) {
                link(p).ifPresent(l -> result.putIfAbsent(l.key(), entry.id()));
            }
        }
        return result;
    }

    /**
     * Loads an object as a local model. If a model is already linked to the key it is refreshed,
     * unless it has unpublished local changes. Returns the local model id.
     */
    public String load(String key) {
        RemoteObject object = bucket.get(key);
        DmnDocument.parse(object.content());
        String existing = linkedModels().get(object.key());
        if (existing != null) {
            return models.atomically(existing, () -> {
                if (localChanged(existing)) {
                    throw new S3StoreException(("Model '%s' is linked to %s and has unpublished local changes. "
                            + "Open it and publish them, or use 'Pull from S3' to discard them.")
                            .formatted(existing, bucket.location(object.key())));
                }
                models.replaceSource(existing, object.content());
                remember(existing, object.key(), object.etag(), object.content());
                return existing;
            });
        }
        String name = object.key().substring(object.key().lastIndexOf('/') + 1);
        String id = models.importXml(name, object.content());
        remember(id, object.key(), object.etag(), object.content());
        return id;
    }

    /** Replaces the local model with the linked object (the previous version stays available to Undo). */
    public void pull(String id) {
        Link link = link(id).orElseThrow(() -> new S3StoreException("This model is not linked to an S3 object"));
        RemoteObject object = bucket.get(link.key());
        DmnDocument.parse(object.content());
        models.atomically(id, () -> {
            models.replaceSource(id, object.content());
            remember(id, object.key(), object.etag(), object.content());
            return null;
        });
    }

    /**
     * Uploads the model to {@code key}. Unless {@code force} is set, the write is conditional: on the
     * linked key it requires the ETag of the last sync, on any other key it requires that nothing exists.
     */
    public String publish(String id, String key, boolean force) {
        String target = bucket.checkKey(key == null || key.isBlank() ? defaultKey(id) : key);
        String xml = models.xml(id);
        Optional<Link> link = link(id);
        String ifMatch = null;
        boolean ifNoneMatch = false;
        if (!force) {
            if (link.isPresent() && link.get().key().equals(target) && link.get().etag() != null) {
                ifMatch = link.get().etag();
            } else {
                ifNoneMatch = true;
            }
        }
        String etag = bucket.put(target, xml, ifMatch, ifNoneMatch);
        remember(id, target, etag, xml);
        return target;
    }

    public void unlink(String id) {
        models.atomically(id, () -> {
            Properties p = store.readMeta(id);
            p.keySet().removeIf(k -> k.toString().startsWith("s3."));
            store.writeMeta(id, p);
            return null;
        });
    }

    public String defaultKey(String id) {
        return link(id).map(Link::key).orElse(bucket.root() + id + ".dmn");
    }

    public Status status(String id) {
        Optional<Link> link = link(id);
        if (link.isEmpty()) {
            return new Status(null, false, Remote.UNKNOWN, null);
        }
        boolean localChanged = localChanged(id);
        try {
            Optional<String> etag = bucket.etag(link.get().key());
            Remote remote = etag.isEmpty() ? Remote.MISSING
                    : etag.get().equals(link.get().etag()) ? Remote.IN_SYNC : Remote.CHANGED;
            return new Status(link.get(), localChanged, remote, null);
        } catch (S3StoreException e) {
            return new Status(link.get(), localChanged, Remote.UNKNOWN, e.getMessage());
        }
    }

    private boolean localChanged(String id) {
        return link(id).map(l -> !sha256(models.xml(id)).equals(l.hash())).orElse(false);
    }

    /** Records a sync; the metadata is read, changed and written under the model's lock. */
    private void remember(String id, String key, String etag, String content) {
        models.atomically(id, () -> {
            Properties p = store.readMeta(id);
            p.setProperty(KEY, key);
            if (etag == null) {
                p.remove(ETAG);
            } else {
                p.setProperty(ETAG, etag);
            }
            p.setProperty(HASH, sha256(content));
            p.setProperty(SYNCED, Instant.now().toString());
            store.writeMeta(id, p);
            return null;
        });
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
