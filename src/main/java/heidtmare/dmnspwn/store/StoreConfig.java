package heidtmare.dmnspwn.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import heidtmare.dmnspwn.config.DmnProperties;

/** File storage, the default ({@code dmnspwn.storage=file}); S3 storage is configured with the S3 client. */
@Configuration(proxyBeanMethods = false)
public class StoreConfig {

    @Bean
    @ConditionalOnProperty(name = "dmnspwn.storage", havingValue = "file", matchIfMissing = true)
    ModelStore fileModelStore(DmnProperties properties) {
        return new FileModelStore(properties);
    }
}
