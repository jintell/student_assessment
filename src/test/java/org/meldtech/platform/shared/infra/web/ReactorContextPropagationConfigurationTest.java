package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ReactorContextPropagationConfigurationTest {

    @Test
    void projectsCorrelationIdIntoMdcAndClearsItAfterTheCallback() {
        ReactorContextPropagationConfiguration configuration =
                new ReactorContextPropagationConfiguration();
        AtomicReference<String> observed = new AtomicReference<>();

        configuration.enableAutomaticPropagation();
        try {
            Mono<String> publisher =
                    Mono.just("signal")
                            .doOnNext(
                                    ignored ->
                                            observed.set(
                                                    MDC.get(
                                                            RequestContextPropagation
                                                                    .CORRELATION_ID_KEY)))
                            .contextWrite(
                                    context ->
                                            context.put(
                                                    RequestContextPropagation.CORRELATION_ID_KEY,
                                                    "01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"));

            StepVerifier.create(publisher).expectNext("signal").verifyComplete();

            assertEquals("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV", observed.get());
            assertNull(MDC.get(RequestContextPropagation.CORRELATION_ID_KEY));
        } finally {
            configuration.disableAutomaticPropagation();
        }
    }

    @Test
    void preservesCarrierAndLoggingContextAcrossSchedulerHops() {
        ReactorContextPropagationConfiguration configuration =
                new ReactorContextPropagationConfiguration();
        ActorContext actor =
                ActorContext.tenantWorkforce(
                        new ActorId("operator-123"),
                        TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347"),
                        CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                        SourceIp.parse("127.0.0.1"));
        RequestContextPropagation propagation = new RequestContextPropagation();
        AtomicReference<Map<String, String>> subscribeOnFields = new AtomicReference<>();
        AtomicReference<Map<String, String>> publishOnFields = new AtomicReference<>();

        configuration.enableAutomaticPropagation();
        try {
            Mono<ActorContext> publisher =
                    Mono.deferContextual(
                                    context -> {
                                        subscribeOnFields.set(loggingFields());
                                        return Mono.just(propagation.require(context));
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .publishOn(Schedulers.parallel())
                            .map(
                                    observedCarrier -> {
                                        publishOnFields.set(loggingFields());
                                        return observedCarrier;
                                    })
                            .contextWrite(context -> propagation.write(context, actor));

            StepVerifier.create(publisher).expectNext(actor).verifyComplete();

            Map<String, String> expected =
                    Map.of(
                            RequestContextPropagation.CORRELATION_ID_KEY,
                            actor.correlationId().toString(),
                            RequestContextPropagation.ACTOR_TYPE_KEY,
                            actor.actorType().name(),
                            RequestContextPropagation.ACTOR_ID_KEY,
                            actor.actorId().toString(),
                            RequestContextPropagation.TENANT_ID_KEY,
                            actor.tenantId().orElseThrow().toString());
            assertEquals(expected, subscribeOnFields.get());
            assertEquals(expected, publishOnFields.get());
            expected.keySet().forEach(field -> assertNull(MDC.get(field)));
        } finally {
            configuration.disableAutomaticPropagation();
        }
    }

    private static Map<String, String> loggingFields() {
        return Map.of(
                RequestContextPropagation.CORRELATION_ID_KEY,
                MDC.get(RequestContextPropagation.CORRELATION_ID_KEY),
                RequestContextPropagation.ACTOR_TYPE_KEY,
                MDC.get(RequestContextPropagation.ACTOR_TYPE_KEY),
                RequestContextPropagation.ACTOR_ID_KEY,
                MDC.get(RequestContextPropagation.ACTOR_ID_KEY),
                RequestContextPropagation.TENANT_ID_KEY,
                MDC.get(RequestContextPropagation.TENANT_ID_KEY));
    }
}
