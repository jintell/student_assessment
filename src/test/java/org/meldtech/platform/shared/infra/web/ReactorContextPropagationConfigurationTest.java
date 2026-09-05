package org.meldtech.platform.shared.infra.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.RequestCarrier;
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
                                    ignored -> observed.set(MDC.get(MdcCorrelationIdAccessor.KEY)))
                            .contextWrite(
                                    context ->
                                            context.put(
                                                    MdcCorrelationIdAccessor.KEY, "request-123"));

            StepVerifier.create(publisher).expectNext("signal").verifyComplete();

            assertEquals("request-123", observed.get());
            assertNull(MDC.get(MdcCorrelationIdAccessor.KEY));
        } finally {
            configuration.disableAutomaticPropagation();
        }
    }

    @Test
    void preservesCarrierAndLoggingContextAcrossSchedulerHops() {
        ReactorContextPropagationConfiguration configuration =
                new ReactorContextPropagationConfiguration();
        RequestCarrier carrier =
                new RequestCarrier("request-456", Optional.empty(), Optional.empty(), "127.0.0.1");
        AtomicReference<String> subscribeOnCorrelationId = new AtomicReference<>();
        AtomicReference<String> publishOnCorrelationId = new AtomicReference<>();

        configuration.enableAutomaticPropagation();
        try {
            Mono<RequestCarrier> publisher =
                    Mono.deferContextual(
                                    context -> {
                                        subscribeOnCorrelationId.set(
                                                MDC.get(MdcCorrelationIdAccessor.KEY));
                                        return Mono.just(context.get(RequestCarrier.class));
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .publishOn(Schedulers.parallel())
                            .map(
                                    observedCarrier -> {
                                        publishOnCorrelationId.set(
                                                MDC.get(MdcCorrelationIdAccessor.KEY));
                                        return observedCarrier;
                                    })
                            .contextWrite(
                                    context ->
                                            context.put(RequestCarrier.class, carrier)
                                                    .put(
                                                            MdcCorrelationIdAccessor.KEY,
                                                            carrier.correlationId()));

            StepVerifier.create(publisher).expectNext(carrier).verifyComplete();

            assertEquals(carrier.correlationId(), subscribeOnCorrelationId.get());
            assertEquals(carrier.correlationId(), publishOnCorrelationId.get());
            assertNull(MDC.get(MdcCorrelationIdAccessor.KEY));
        } finally {
            configuration.disableAutomaticPropagation();
        }
    }
}
