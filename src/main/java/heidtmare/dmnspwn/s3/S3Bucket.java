package heidtmare.dmnspwn.s3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import heidtmare.dmnspwn.config.DmnProperties;
import heidtmare.dmnspwn.store.ModelSummary;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Thin wrapper over the S3 client, confined to the configured bucket and key prefix.
 * Translates SDK failures into user-visible {@link S3StoreException}s.
 */
@Service
@ConditionalOnS3Enabled
public class S3Bucket {

    private static final int MAX_KEY_LENGTH = 1024;

    public record Entry(String key, String name, long size, Instant lastModified) {
        public String modifiedText() {
            return lastModified == null ? "" : ModelSummary.FORMAT.format(lastModified);
        }

        public String sizeText() {
            return size < 1024 ? size + " B" : String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        }
    }

    public record Folder(String prefix, String name) {
    }

    /** One page of a "folder" listing; {@code parent} is null at the configured root. */
    public record Listing(String prefix, String parent, List<Folder> folders, List<Entry> objects,
                          String nextToken) {
    }

    public record RemoteObject(String key, String content, String etag) {
    }

    private final S3Client s3;
    private final DmnProperties.S3 config;

    public S3Bucket(S3Client s3, DmnProperties properties) {
        this.s3 = s3;
        this.config = properties.s3();
    }

    public String bucket() {
        return config.bucket();
    }

    public String root() {
        return config.root();
    }

    public Listing list(String prefix, String continuationToken) {
        String p = folder(prefix);
        try {
            ListObjectsV2Response response = s3.listObjectsV2(b -> b.bucket(bucket()).prefix(p).delimiter("/")
                    .maxKeys(500).continuationToken(blankToNull(continuationToken)));
            List<Folder> folders = new ArrayList<>();
            for (CommonPrefix cp : response.commonPrefixes()) {
                String name = cp.prefix().substring(p.length());
                folders.add(new Folder(cp.prefix(), name.endsWith("/") ? name.substring(0, name.length() - 1) : name));
            }
            List<Entry> objects = new ArrayList<>();
            for (S3Object o : response.contents()) {
                if (!o.key().equals(p)) {
                    objects.add(new Entry(o.key(), o.key().substring(p.length()), o.size() == null ? 0 : o.size(),
                            o.lastModified()));
                }
            }
            return new Listing(p, parent(p), folders, objects,
                    Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null);
        } catch (SdkException e) {
            throw translate(e, "list " + location(p));
        }
    }

    public RemoteObject get(String key) {
        String k = checkKey(key);
        try {
            ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket()).key(k));
            return new RemoteObject(k, readBody(in, config.maxObjectSize().toBytes(), location(k)),
                    in.response().eTag());
        } catch (SdkException e) {
            throw translate(e, "read " + location(k));
        }
    }

    /** Current ETag of an object, or empty when it does not exist. */
    public Optional<String> etag(String key) {
        String k = checkKey(key);
        try {
            return Optional.ofNullable(s3.headObject(b -> b.bucket(bucket()).key(k)).eTag());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw translate(e, "inspect " + location(k));
        } catch (SdkException e) {
            throw translate(e, "inspect " + location(k));
        }
    }

    /**
     * Uploads a model. With {@code ifMatch} the write only succeeds if the object still has that ETag;
     * with {@code ifNoneMatch} only if no object exists yet. Returns the new ETag.
     */
    public String put(String key, String xml, String ifMatch, boolean ifNoneMatch) {
        String k = checkKey(key);
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket()).key(k)
                .contentType("application/xml; charset=utf-8");
        if (ifMatch != null) {
            request.ifMatch(ifMatch);
        } else if (ifNoneMatch) {
            request.ifNoneMatch("*");
        }
        try {
            return s3.putObject(request.build(), RequestBody.fromString(xml, StandardCharsets.UTF_8)).eTag();
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || e.statusCode() == 409) {
                throw new S3StoreException.Conflict(k, ifMatch != null
                        ? location(k) + " was changed in S3 since this model was last loaded or published."
                        : "An object already exists at " + location(k) + ".");
            }
            throw translate(e, "write " + location(k));
        } catch (SdkException e) {
            throw translate(e, "write " + location(k));
        }
    }

    /** Validates a model key: inside the configured prefix, a {@code .dmn}/{@code .xml} file, no traversal. */
    public String checkKey(String key) {
        String k = key == null ? "" : key.strip();
        String lower = k.toLowerCase(Locale.ROOT);
        if (k.isEmpty() || k.length() > MAX_KEY_LENGTH || k.startsWith("/") || k.contains("//")
                || k.contains("\\") || ("/" + k + "/").contains("/../") || ("/" + k + "/").contains("/./")) {
            throw new S3StoreException("Invalid S3 key '" + k + "'");
        }
        if (!k.startsWith(root())) {
            throw new S3StoreException("S3 keys must start with the configured prefix '" + root() + "'");
        }
        if (!lower.endsWith(".dmn") && !lower.endsWith(".xml")) {
            throw new S3StoreException("S3 keys must end with .dmn or .xml");
        }
        return k;
    }

    public String location(String key) {
        return location(bucket(), key);
    }

    static String location(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    private String folder(String prefix) {
        String p = prefix == null || prefix.isBlank() ? root() : prefix.strip();
        if (!p.isEmpty() && !p.endsWith("/")) {
            p += "/";
        }
        if (!p.startsWith(root()) || ("/" + p).contains("/../")) {
            throw new S3StoreException("Folder '" + p + "' is outside the configured prefix '" + root() + "'");
        }
        return p;
    }

    private String parent(String folder) {
        if (folder.equals(root())) {
            return null;
        }
        String trimmed = folder.substring(0, folder.length() - 1);
        int slash = trimmed.lastIndexOf('/');
        String parent = slash < 0 ? "" : trimmed.substring(0, slash + 1);
        return parent.length() < root().length() ? root() : parent;
    }

    /** Reads and closes an object's content as UTF-8, failing for objects larger than {@code max} bytes. */
    static String readBody(ResponseInputStream<GetObjectResponse> in, long max, String location) {
        Long length = in.response().contentLength();
        if (length != null && length > max) {
            in.abort();
            throw new S3StoreException("%s is %d bytes; the limit is %d".formatted(location, length, max));
        }
        try (in) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            if (bytes.length > max) {
                throw new S3StoreException(location + " exceeds the size limit of " + max + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new S3StoreException("Reading " + location + " failed: " + e.getMessage());
        }
    }

    private S3StoreException translate(SdkException e, String action) {
        return translate(e, action, bucket());
    }

    /** A user-visible message for an SDK failure. */
    static S3StoreException translate(SdkException e, String action, String bucket) {
        if (e instanceof S3Exception s3e && s3e.awsErrorDetails() != null) {
            String code = s3e.awsErrorDetails().errorCode();
            String detail = switch (code == null ? "" : code) {
                case "NoSuchKey" -> "the object does not exist";
                case "NoSuchBucket" -> "bucket '" + bucket + "' does not exist";
                case "AccessDenied" -> "access denied (check the IAM permissions for this bucket)";
                case "InvalidAccessKeyId", "SignatureDoesNotMatch", "ExpiredToken" ->
                        "the AWS credentials were rejected (" + code + ")";
                default -> s3e.awsErrorDetails().errorMessage() + (code == null ? "" : " (" + code + ")");
            };
            return new S3StoreException("Could not " + action + ": " + detail);
        }
        return new S3StoreException("Could not " + action + ": " + e.getMessage());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
