package io.iconator.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

import static org.springframework.boot.SpringApplication.run;

@SpringBootApplication
@PropertySources({
        @PropertySource(value = "classpath:email.application.properties"),
        @PropertySource(value = "classpath:email.application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
})
public class EmailApplication {
    private static final Logger LOG = LoggerFactory.getLogger(EmailApplication.class);

    public static void main(String[] args) {
        try {
            run(EmailApplication.class, args);
        } catch (Throwable t) {
            //ignore silent exception
            if (!t.getClass().toString().endsWith("SilentExitException")) {
                LOG.error("cannot execute core", t);
            }
        }
    }

}
