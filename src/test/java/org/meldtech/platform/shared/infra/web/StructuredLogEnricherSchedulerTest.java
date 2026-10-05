package org.meldtech.platform.shared.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.observability.StructuredLogEnricher;
import org.meldtech.platform.platform.infra.observability.StructuredLogEvent;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class StructuredLogEnricherSchedulerTest {

    @Test
    void enrichesALogEventAfterAReactorSchedulerHop() {
        ReactorContextPropagationConfiguration configuration =
                new ReactorContextPropagationConfiguration();
        RequestContextPropagation propagation = new RequestContextPropagation();
        ActorContext actor =
                ActorContext.tenantWorkforce(
                        new ActorId("operator-123"),
                        TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347"),
                        CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                        SourceIp.parse("127.0.0.1"));
        StructuredLogEnricher enricher =
                new StructuredLogEnricher(
                        Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC),
                        StructuredLogEvent.RuntimeRole.API);
        AtomicReference<StructuredLogEvent> captured = new AtomicReference<>();

        configuration.enableAutomaticPropagation();
        try {
            Mono<String> publisher =
                    Mono.just("request")
                            .publishOn(Schedulers.parallel())
                            .map(
                                    value -> {
                                        captured.set(enricher.enrich(statement()));
                                        return value;
                                    })
                            .contextWrite(context -> propagation.write(context, actor));

            StepVerifier.create(publisher).expectNext("request").verifyComplete();

            StructuredLogEvent event = Objects.requireNonNull(captured.get());
            assertThat(event.correlationId()).isEqualTo(actor.correlationId());
            assertThat(event.actorId()).contains(actor.actorId());
            assertThat(event.actorType()).contains(actor.actorType());
            assertThat(event.tenantId()).isEqualTo(actor.tenantId());
        } finally {
            configuration.disableAutomaticPropagation();
        }
    }

    private static StructuredLogEnricher.LogStatement statement() {
        return new StructuredLogEnricher.LogStatement(
                StructuredLogEvent.Level.INFO,
                "example.Logger",
                "Request completed",
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                "platform",
                "getConformanceReference",
                Optional.empty(),
                Optional.empty(),
                Optional.of(12L),
                Optional.of(2L),
                Optional.empty());
    }
}
