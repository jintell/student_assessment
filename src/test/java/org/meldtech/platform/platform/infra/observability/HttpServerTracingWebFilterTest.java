package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.SpanKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class HttpServerTracingWebFilterTest {

    private static final CorrelationId CORRELATION_ID =
            CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV");

    @Test
    void createsAHttpServerParentForTheSliceSpan() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(HttpServerTracingWebFilterTest.class)) {
            PlatformTracer tracer = new PlatformTracer(telemetry.tracer());
            HttpServerTracingWebFilter filter = new HttpServerTracingWebFilter(tracer);
            OpenTelemetryRequestTelemetry requestTelemetry =
                    new OpenTelemetryRequestTelemetry(tracer, () -> java.time.Instant.EPOCH);
            MockServerWebExchange exchange =
                    MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/reference"));

            Mono<Void> request =
                    filter.filter(
                                    exchange,
                                    ignored -> {
                                        exchange.getResponse().setStatusCode(HttpStatus.OK);
                                        return Mono.from(
                                                requestTelemetry.observe(metadata(), Mono.empty()));
                                    })
                            .contextWrite(
                                    context ->
                                            context.put(
                                                            RequestContextPropagation
                                                                    .CORRELATION_ID_KEY,
                                                            CORRELATION_ID.toString())
                                                    .put(ActorContext.class, actor()));

            StepVerifier.create(request).verifyComplete();

            var spans = telemetry.finishedSpans();
            var server =
                    spans.stream()
                            .filter(span -> span.getKind() == SpanKind.SERVER)
                            .findFirst()
                            .orElseThrow();
            var slice =
                    spans.stream()
                            .filter(
                                    span ->
                                            span.getName()
                                                    .equals("platform.getConformanceReference"))
                            .findFirst()
                            .orElseThrow();
            assertThat(slice.getParentSpanId()).isEqualTo(server.getSpanId());
            assertThat(slice.getTraceId()).isEqualTo(server.getTraceId());
        }
    }

    private static RequestTelemetry.RequestMetadata metadata() {
        return new RequestTelemetry.RequestMetadata(
                "platform",
                "getConformanceReference",
                RequestTelemetry.Audience.OPERATOR,
                RequestTelemetry.Operation.READ,
                RequestTelemetry.RouteClass.STANDARD);
    }

    private static ActorContext actor() {
        return new ActorContext(
                org.meldtech.platform.shared.kernel.context.ActorType.WORKFORCE_USER,
                new ActorId("operator-123"),
                Optional.of(TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347")),
                CORRELATION_ID,
                SourceIp.parse("127.0.0.1"),
                Optional.empty());
    }
}
