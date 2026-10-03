package heidtmare.dmnspwn.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import heidtmare.dmnspwn.store.ModelRepository;

/** Copies the bundled sample models into an empty storage directory on startup. */
@Component
public class SampleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleSeeder.class);

    private final ModelRepository repository;
    private final DmnProperties properties;

    public SampleSeeder(ModelRepository repository, DmnProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.seedSamples() || !repository.ids().isEmpty()) {
            return;
        }
        Resource[] samples = new PathMatchingResourcePatternResolver().getResources("classpath:samples/*.dmn");
        for (Resource sample : samples) {
            String name = sample.getFilename();
            if (name == null) {
                continue;
            }
            repository.write(name.replace(".dmn", ""), sample.getContentAsString(StandardCharsets.UTF_8), false);
        }
        log.info("Seeded {} sample model(s) into {}", samples.length, repository.directory());
    }
}
