package org.meldtech.platform.migration.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;

class BackfillWorkerApplicationTest {

    @Test
    void workerProfileRunsTheConfiguredBackfillOperation() {
        var executed = new AtomicReference<String>();
        BackfillOperationRegistry registry =
                operation -> {
                    executed.set(operation);
                    return Mono.just(new BackfillRunResult(BackfillRunResult.Status.COMPLETED, 1));
                };

        new ApplicationContextRunner()
                .withUserConfiguration(BackfillWorkerApplication.class)
                .withBean(BackfillOperationRegistry.class, () -> registry)
                .withPropertyValues(
                        "spring.profiles.active=worker",
                        "cbt.backfill.operation=answer-normalization:1")
                .run(
                        context -> {
                            ApplicationRunner runner = context.getBean(ApplicationRunner.class);
                            ApplicationArguments arguments =
                                    new DefaultApplicationArguments(new String[0]);
                            runner.run(arguments);
                            assertThat(executed.get()).isEqualTo("answer-normalization:1");
                        });
    }
}
