package heidtmare.dmnspwn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/** In-memory S3 with ETags and conditional writes (If-Match / If-None-Match), for tests. */
public class FakeS3Client implements S3Client {

    record Stored(byte[] bytes, String etag, Instant modified) {
    }

    final Map<String, Stored> objects = new TreeMap<>();
    private final AtomicInteger version = new AtomicInteger();
    final List<PutObjectRequest> puts = new ArrayList<>();

    public void store(String key, String content) {
        objects.put(key, new Stored(content.getBytes(StandardCharsets.UTF_8), "\"v" + version.incrementAndGet() + "\"",
                Instant.now()));
    }

    public String content(String key) {
        return new String(objects.get(key).bytes(), StandardCharsets.UTF_8);
    }

    @Override
    public String serviceName() {
        return "s3";
    }

    @Override
    public void close() {
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
        String prefix = request.prefix() == null ? "" : request.prefix();
        List<S3Object> contents = new ArrayList<>();
        List<CommonPrefix> prefixes = new ArrayList<>();
        objects.forEach((key, o) -> {
            if (!key.startsWith(prefix)) {
                return;
            }
            int slash = key.indexOf('/', prefix.length());
            if (request.delimiter() != null && slash >= 0) {
                CommonPrefix cp = CommonPrefix.builder().prefix(key.substring(0, slash + 1)).build();
                if (!prefixes.contains(cp)) {
                    prefixes.add(cp);
                }
            } else {
                contents.add(S3Object.builder().key(key).size((long) o.bytes().length).lastModified(o.modified())
                        .eTag(o.etag()).build());
            }
        });
        return ListObjectsV2Response.builder().contents(contents).commonPrefixes(prefixes).isTruncated(false).build();
    }

    @Override
    public <T> T getObject(GetObjectRequest request, ResponseTransformer<GetObjectResponse, T> transformer) {
        Stored o = objects.get(request.key());
        if (o == null) {
            throw notFound();
        }
        GetObjectResponse response = GetObjectResponse.builder().eTag(o.etag()).contentLength((long) o.bytes().length)
                .build();
        try {
            return transformer.transform(response, AbortableInputStream.create(new ByteArrayInputStream(o.bytes())));
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest request) {
        Stored o = objects.get(request.key());
        if (o == null) {
            throw notFound();
        }
        return HeadObjectResponse.builder().eTag(o.etag()).contentLength((long) o.bytes().length).build();
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        puts.add(request);
        Stored existing = objects.get(request.key());
        if (request.ifMatch() != null && (existing == null || !existing.etag().equals(request.ifMatch()))
                || "*".equals(request.ifNoneMatch()) && existing != null) {
            throw (S3Exception) S3Exception.builder().statusCode(412)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed")
                            .errorMessage("At least one of the pre-conditions you specified did not hold").build())
                    .build();
        }
        try (var in = body.contentStreamProvider().newStream()) {
            store(request.key(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return PutObjectResponse.builder().eTag(objects.get(request.key()).etag()).build();
    }

    private static NoSuchKeyException notFound() {
        return (NoSuchKeyException) NoSuchKeyException.builder().statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").errorMessage("Not found").build())
                .build();
    }
}
