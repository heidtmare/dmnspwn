package heidtmare.dmnspwn.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import heidtmare.dmnspwn.s3.ConditionalOnS3Enabled;
import heidtmare.dmnspwn.s3.S3ModelStore;
import heidtmare.dmnspwn.store.FileModelStore;
import heidtmare.dmnspwn.store.ModelStore;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** The model store selected by {@code dmnspwn.storage}, and the S3 client when S3 is enabled. */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    /** {@link DmnProperties} ensures that {@code s3} storage comes with the S3 client ({@code dmnspwn.s3.enabled}). */
    @Bean
    ModelStore modelStore(DmnProperties properties, ObjectProvider<S3Client> s3Client) {
        return switch (properties.storage()) {
            case FILE -> new FileModelStore(properties);
            case S3 -> new S3ModelStore(s3Client.getObject(), properties);
        };
    }

    /**
     * S3 client for {@code dmnspwn.s3.enabled=true}. Credentials come from the SDK's default provider chain
     * (environment, {@code ~/.aws}, SSO, container / instance roles).
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnS3Enabled
    S3Client s3Client(DmnProperties properties) {
        DmnProperties.S3 s3 = properties.s3();
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
