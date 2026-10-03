package heidtmare.dmnspwn.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param storageDirectory where models are stored as {@code .dmn} files
 * @param seedSamples      copy the bundled sample models into an empty storage directory
 * @param historySize      number of previous versions kept per model for undo
 * @param s3               optional AWS S3 bucket to load models from and publish them to
 */
@ConfigurationProperties("dmnspwn")
public record DmnProperties(@DefaultValue("./dmn-models") Path storageDirectory,
                            @DefaultValue("true") boolean seedSamples,
                            @DefaultValue("50") int historySize,
                            @DefaultValue S3 s3) {

    /**
     * @param enabled       turns the S3 integration on
     * @param bucket        bucket name
     * @param prefix        key prefix ("folder") the application may read and write, e.g. {@code dmn/}
     * @param region        AWS region; when empty the SDK's default region provider chain is used
     * @param endpoint      endpoint override for S3-compatible stores (MinIO, LocalStack)
     * @param pathStyle     use path-style addressing (needed by most S3-compatible stores)
     * @param maxObjectSize largest object that will be loaded
     * @param timeout       overall timeout for one S3 API call
     */
    public record S3(@DefaultValue("false") boolean enabled,
                     String bucket,
                     @DefaultValue("") String prefix,
                     String region,
                     URI endpoint,
                     @DefaultValue("false") boolean pathStyle,
                     @DefaultValue("10MB") DataSize maxObjectSize,
                     @DefaultValue("30s") Duration timeout) {

        /** The prefix as a folder: empty, or ending with exactly one {@code /} and no leading slash. */
        public String root() {
            String p = prefix == null ? "" : prefix.strip().replaceAll("^/+", "");
            return p.isEmpty() || p.endsWith("/") ? p : p + "/";
        }
    }
}
