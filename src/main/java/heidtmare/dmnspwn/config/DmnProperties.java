package heidtmare.dmnspwn.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param storage          where models are stored: {@code file} (one instance) or {@code s3} (shared by instances)
 * @param storageDirectory where {@code file} storage keeps models as {@code .dmn} files
 * @param seedSamples      copy the bundled sample models into an empty store
 * @param historySize      number of previous versions kept per model for undo
 * @param s3               optional AWS S3 bucket to load models from and publish them to, and for {@code s3} storage
 */
@ConfigurationProperties("dmnspwn")
public record DmnProperties(@DefaultValue("file") Storage storage,
                            @DefaultValue("./dmn-models") Path storageDirectory,
                            @DefaultValue("true") boolean seedSamples,
                            @DefaultValue("50") int historySize,
                            @DefaultValue S3 s3) {

    public enum Storage { FILE, S3 }

    public DmnProperties {
        if (s3.enabled() && (s3.bucket() == null || s3.bucket().isBlank())) {
            throw new IllegalArgumentException("dmnspwn.s3.bucket must be set when dmnspwn.s3.enabled=true");
        }
        if (storage == Storage.S3) {
            if (!s3.enabled()) {
                throw new IllegalArgumentException("dmnspwn.storage=s3 requires dmnspwn.s3.enabled=true");
            }
            String store = s3.storageRoot();
            String root = s3.root();
            if (store.isEmpty() || store.startsWith(root) || root.startsWith(store)) {
                throw new IllegalArgumentException(("dmnspwn.s3.storage-prefix ('%s') must be set and must not "
                        + "overlap dmnspwn.s3.prefix ('%s')").formatted(store, root));
            }
        }
    }

    /**
     * @param enabled       turns the S3 integration on
     * @param bucket        bucket name
     * @param prefix        key prefix ("folder") that models are loaded from and published to, e.g. {@code dmn/}
     * @param storagePrefix key prefix under which {@code s3} storage keeps models, their history and tests
     * @param region        AWS region; when empty the SDK's default region provider chain is used
     * @param endpoint      endpoint override for S3-compatible stores (MinIO, LocalStack)
     * @param pathStyle     use path-style addressing (needed by most S3-compatible stores)
     * @param maxObjectSize largest object that will be loaded
     * @param timeout       overall timeout for one S3 API call
     */
    public record S3(@DefaultValue("false") boolean enabled,
                     String bucket,
                     @DefaultValue("dmn/") String prefix,
                     @DefaultValue("dmnspwn/") String storagePrefix,
                     String region,
                     URI endpoint,
                     @DefaultValue("false") boolean pathStyle,
                     @DefaultValue("10MB") DataSize maxObjectSize,
                     @DefaultValue("30s") Duration timeout) {

        /** The prefix as a folder: empty, or ending with exactly one {@code /} and no leading slash. */
        public String root() {
            return folder(prefix);
        }

        /** The storage prefix as a folder, like {@link #root()}. */
        public String storageRoot() {
            return folder(storagePrefix);
        }

        private static String folder(String prefix) {
            String p = prefix == null ? "" : prefix.strip().replaceAll("^/+", "");
            return p.isEmpty() || p.endsWith("/") ? p : p + "/";
        }
    }
}
