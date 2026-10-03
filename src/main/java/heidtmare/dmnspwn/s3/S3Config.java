package heidtmare.dmnspwn.s3;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import heidtmare.dmnspwn.config.DmnProperties;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * S3 client for {@code dmnspwn.s3.enabled=true}. Credentials come from the SDK's default
 * provider chain (environment, {@code ~/.aws}, SSO, container / instance roles).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dmnspwn.s3", name = "enabled", havingValue = "true")
public class S3Config {

    @Bean(destroyMethod = "close")
    S3Client s3Client(DmnProperties properties) {
        DmnProperties.S3 s3 = properties.s3();
        if (s3.bucket() == null || s3.bucket().isBlank()) {
            throw new IllegalStateException("dmnspwn.s3.bucket must be set when dmnspwn.s3.enabled=true");
        }
        S3ClientBuilder builder = S3Client.builder()
                .forcePathStyle(s3.pathStyle())
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(s3.timeout()).build());
        if (s3.region() != null && !s3.region().isBlank()) {
            builder.region(Region.of(s3.region().strip()));
        }
        if (s3.endpoint() != null) {
            builder.endpointOverride(s3.endpoint());
        }
        return builder.build();
    }
}
