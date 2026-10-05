package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Statement;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.observability.RequestTelemetry;
import org.meldtech.platform.testing.observability.ObservabilityTestFixture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TracingConnectionFactoryTest {

    @Test
    void emitsOnlyTheParsedStatementNameAndNeverReadsABindValue() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(TracingConnectionFactoryTest.class)) {
            Connection connection = connection();
            ConnectionFactory delegate = connectionFactory(connection);
            TracingConnectionFactory tracing =
                    new TracingConnectionFactory(
                            delegate,
                            telemetry.openTelemetry(),
                            DatabaseQueryTelemetry.NOOP,
                            () -> java.time.Instant.EPOCH);
            Object bindValue =
                    new Object() {
                        @Override
                        public String toString() {
                            throw new AssertionError("bind value must never be rendered");
                        }
                    };
            String sql = "SELECT answer FROM delivery.answer WHERE pin = $1";

            StepVerifier.create(
                            Flux.from(tracing.create())
                                    .flatMap(
                                            created ->
                                                    Flux.from(
                                                            created.createStatement(sql)
                                                                    .bind(0, bindValue)
                                                                    .execute())))
                    .verifyComplete();

            var span = telemetry.finishedSpans().getFirst();
            assertThat(span.getName()).isEqualTo("db.select");
            assertThat(span.getAttributes().asMap().toString())
                    .doesNotContain("SELECT")
                    .doesNotContain("answer")
                    .doesNotContain("pin");
        }
    }

    @Test
    void countsEveryExecutedStatementAndRecordsItsDurationBySlice() {
        try (ObservabilityTestFixture telemetry =
                ObservabilityTestFixture.create(OpenTelemetryRequestTelemetry.class)) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            FoundationMetrics metrics = new FoundationMetrics(registry);
            AtomicLong elapsedMillis = new AtomicLong();
            org.meldtech.platform.shared.kernel.time.Clock clock =
                    () -> Instant.ofEpochMilli(elapsedMillis.getAndAdd(5));
            TracingConnectionFactory tracing =
                    new TracingConnectionFactory(
                            connectionFactory(connection()),
                            telemetry.openTelemetry(),
                            metrics,
                            clock);
            OpenTelemetryRequestTelemetry requests =
                    new OpenTelemetryRequestTelemetry(telemetry.openTelemetry(), clock);
            RequestTelemetry.RequestMetadata metadata = metadata();

            StepVerifier.create(
                            Flux.from(
                                            requests.observe(
                                                    metadata,
                                                    Flux.from(tracing.create())
                                                            .flatMap(
                                                                    created ->
                                                                            Flux.from(
                                                                                    created.createStatement(
                                                                                                    "SELECT 1")
                                                                                            .execute()))))
                                    .contextWrite(
                                            context -> context.put(ActorContext.class, actor())))
                    .verifyComplete();

            assertThat(
                            registry.get("db_query_duration")
                                    .tag("slice", metadata.slice())
                                    .tag("operation", "read")
                                    .timer()
                                    .count())
                    .isEqualTo(1L);
            assertThat(telemetry.logEvents()).hasSize(1);
            Map<String, Object> fields =
                    telemetry.logEvents().getFirst().getKeyValuePairs().stream()
                            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(fields)
                    .containsEntry("module", metadata.module())
                    .containsEntry("slice", metadata.slice())
                    .containsEntry("dbQueryCount", 1L);
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
                ActorType.WORKFORCE_USER,
                new ActorId("operator-123"),
                Optional.empty(),
                CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                SourceIp.parse("127.0.0.1"),
                Optional.empty());
    }

    private static ConnectionFactory connectionFactory(Connection connection) {
        return new ConnectionFactory() {
            @Override
            public org.reactivestreams.Publisher<? extends Connection> create() {
                return Mono.just(connection);
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return () -> "test";
            }
        };
    }

    private static Connection connection() {
        Statement statement =
                (Statement)
                        Proxy.newProxyInstance(
                                Statement.class.getClassLoader(),
                                new Class<?>[] {Statement.class},
                                (proxy, method, arguments) ->
                                        switch (method.getName()) {
                                            case "bind",
                                                    "bindNull",
                                                    "add",
                                                    "fetchSize",
                                                    "returnGeneratedValues" ->
                                                    proxy;
                                            case "execute" -> Flux.empty();
                                            default ->
                                                    throw new UnsupportedOperationException(
                                                            method.getName());
                                        });
        io.r2dbc.spi.ConnectionMetadata metadata =
                io.r2dbc.spi.ConnectionMetadata.class.cast(
                        Proxy.newProxyInstance(
                                io.r2dbc.spi.ConnectionMetadata.class.getClassLoader(),
                                new Class<?>[] {io.r2dbc.spi.ConnectionMetadata.class},
                                (proxy, method, arguments) -> "test"));
        return Connection.class.cast(
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "createStatement" -> statement;
                                    case "getMetadata" -> metadata;
                                    case "isAutoCommit" -> true;
                                    case "getTransactionIsolationLevel" ->
                                            io.r2dbc.spi.IsolationLevel.READ_COMMITTED;
                                    case "validate" -> Mono.just(true);
                                    default -> Mono.empty();
                                }));
    }
}
