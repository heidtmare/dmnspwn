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

import heidtmare.dmnspwn.store.ModelStore;
import heidtmare.dmnspwn.store.StoreConflictException;

/** Copies the bundled sample models into an empty store on startup. */
@Component
public class SampleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleSeeder.class);

    private final ModelStore store;
    private final DmnProperties properties;

    public SampleSeeder(ModelStore store, DmnProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.seedSamples() || !store.list().isEmpty()) {
            return;
        }
        Resource[] samples = new PathMatchingResourcePatternResolver().getResources("classpath:samples/*.dmn");
        int seeded = 0;
        for (Resource sample : samples) {
            String name = sample.getFilename();
            if (name == null) {
                continue;
            }
            try {
                store.write(name.replace(".dmn", ""), sample.getContentAsString(StandardCharsets.UTF_8), null, false);
                seeded++;
            } catch (StoreConflictException e) {
                // another instance starting at the same time seeded it
            }
        }
        log.info("Seeded {} sample model(s) into {}", seeded, store.location());
    }
}
