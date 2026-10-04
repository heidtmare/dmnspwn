package heidtmare.dmnspwn.s3;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Registers the bean only when S3 storage is enabled ({@code dmnspwn.s3.enabled=true}). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "dmnspwn.s3", name = "enabled", havingValue = "true")
public @interface ConditionalOnS3Enabled {
}
