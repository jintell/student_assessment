package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RelayBatchTracingTest {

    @Test
    void batchSpanIsLazyAndEndsForEverySubscription() {
        try (var telemetry = ObservabilityTestFixture.create(RelayBatchTracingTest.class)) {
            Mono<Void> batch = relay(telemetry).publishSequentially(Flux.empty());
            assertThat(telemetry.finishedSpans()).isEmpty();
            StepVerifier.create(batch).verifyComplete();
            StepVerifier.create(batch).verifyComplete();
            assertThat(telemetry.finishedSpans())
                    .hasSize(2)
                    .allSatisfy(
                            span -> {
                                assertThat(span.getName()).isEqualTo("outbox relay batch");
                                assertThat(span.getParentSpanContext().isValid()).isFalse();
                                assertThat(
                                                span.getAttributes()
                                                        .get(AttributeKey.stringKey("outcome")))
                                        .isEqualTo("success");
                            });
        }
    }

    @Test
    void errorsAndCancellationEndSpansWithoutDriverDetails() {
        try (var telemetry = ObservabilityTestFixture.create(RelayBatchTracingTest.class)) {
            IllegalStateException failure =
                    new IllegalStateException("credential-shaped-driver-detail");
            StepVerifier.create(relay(telemetry).publishSequentially(Flux.error(failure)))
                    .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                    .verify();
            StepVerifier.create(relay(telemetry).publishSequentially(Flux.never()))
                    .thenCancel()
                    .verify();
            assertThat(telemetry.finishedSpans()).hasSize(2);
            assertThat(telemetry.finishedSpans().getFirst().getStatus().getStatusCode())
                    .isEqualTo(StatusCode.ERROR);
            assertThat(
                            telemetry
                                    .finishedSpans()
                                    .getLast()
                                    .getAttributes()
                                    .get(AttributeKey.stringKey("outcome")))
                    .isEqualTo("cancelled");
            assertThat(telemetry.finishedSpans())
                    .allSatisfy(
                            span -> {
                                assertThat(span.getEvents()).isEmpty();
                                assertThat(span.getStatus().getDescription()).isEmpty();
                                assertThat(span.getAttributes().asMap()).hasSize(1);
                            });
        }
    }

    private static RelayPublishStep relay(ObservabilityTestFixture telemetry) {
        return new RelayPublishStep(
                mock(OutboxBrokerPublisher.class),
                mock(OutboxPublicationStore.class),
                mock(OutboxAlertSink.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                mock(OutboxTelemetry.class),
                telemetry.tracer());
    }
}
