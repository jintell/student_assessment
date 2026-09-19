package org.meldtech.platform.migration.backfill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
@ConditionalOnProperty(name = "cbt.backfill.operation")
public class BackfillWorkerApplication {

    @Bean
    ApplicationRunner backfillWorkerRunner(
            BackfillOperationRegistry operations,
            @Value("${cbt.backfill.operation}") String operation) {
        if (operation.isBlank()) {
            throw new IllegalArgumentException("cbt.backfill.operation must not be blank");
        }
        return arguments -> operations.run(operation).toFuture().get();
    }
}
