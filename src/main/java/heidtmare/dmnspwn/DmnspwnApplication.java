package heidtmare.dmnspwn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DmnspwnApplication {

    public static void main(String[] args) {
        SpringApplication.run(DmnspwnApplication.class, args);
    }
}
