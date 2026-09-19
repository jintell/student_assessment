package org.meldtech.platform.platform.infra.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SessionWindowQuery;
import org.meldtech.platform.platform.api.SessionWindowReason;
import org.meldtech.platform.platform.api.SessionWindowResult;
import org.meldtech.platform.platform.api.SessionWindowState;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SessionWindowQueryConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(SessionWindowQueryConfiguration.class);

    @Test
    void registersAnUnknownDefaultOnlyWhenNoRealAdapterExists() {
        Instant decisionTime = Instant.parse("2026-09-17T12:00:00Z");

        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(SessionWindowQuery.class);
                    StepVerifier.create(
                                    context.getBean(SessionWindowQuery.class)
                                            .query("production", decisionTime))
                            .assertNext(
                                    result -> {
                                        assertThat(result.state())
                                                .isEqualTo(SessionWindowState.UNKNOWN);
                                        assertThat(result.reason())
                                                .isEqualTo(
                                                        SessionWindowReason.SOURCE_NOT_CONFIGURED);
                                    })
                            .verifyComplete();
                });
    }

    @Test
    void backsOffForAnAuthoritativeAdapter() {
        SessionWindowQuery realAdapter =
                (environment, decisionTime) ->
                        Mono.just(
                                new SessionWindowResult(
                                        SessionWindowState.NONE,
                                        SessionWindowReason.AUTHORITATIVE_NONE,
                                        "scheduling",
                                        decisionTime,
                                        Optional.empty()));

        contextRunner
                .withBean(SessionWindowQuery.class, () -> realAdapter)
                .run(
                        context ->
                                assertThat(context.getBean(SessionWindowQuery.class))
                                        .isSameAs(realAdapter));
    }
}
