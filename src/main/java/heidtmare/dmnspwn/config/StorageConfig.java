package heidtmare.dmnspwn.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import heidtmare.dmnspwn.s3.S3ModelStore;
import heidtmare.dmnspwn.store.FileModelStore;
import heidtmare.dmnspwn.store.ModelStore;

import software.amazon.awssdk.services.s3.S3Client;

/** The model store selected by {@code dmnspwn.storage}. */
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
}
