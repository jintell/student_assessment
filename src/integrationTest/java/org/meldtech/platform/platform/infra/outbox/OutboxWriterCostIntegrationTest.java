package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.OutboxEventId;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.meldtech.platform.shared.kernel.outbox.AggregateReference;
import org.meldtech.platform.shared.kernel.outbox.OutboxMessage;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class OutboxWriterCostIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final int WARMUP_PAIRS = 20;
    private static final int SAMPLE_PAIRS = 100;
    private static final TenantId TENANT = TenantId.parse("01950f47-6000-7000-8000-000000000001");
    private static final CorrelationId CORRELATION =
            CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("outbox-cost-test"),
                    TENANT,
                    CORRELATION,
                    SourceIp.parse("127.0.0.1"));

    @Test
    void measuresOneExtraInsertOnTheExistingTransactionConnection() throws Exception {
        ConnectionPool pool =
                new ConnectionPool(
                        ConnectionPoolConfiguration.builder(connectionFactory)
                                .initialSize(1)
                                .maxSize(10)
                                .build());
        CountingConnections measured = new CountingConnections(pool);
        databaseClient = DatabaseClient.create(measured);
        transactions = TransactionalOperator.create(new R2dbcTransactionManager(measured));
        ReactiveOutboxWriter writer =
                new ReactiveOutboxWriter(
                        databaseClient,
                        new RegisteredEventSchemaValidator(
                                Path.of("contracts/events"), new ObjectMapper()),
                        new OutboxContextCarrier(),
                        mock(OutboxTelemetry.class));
        List<Long> baselineTimes = new ArrayList<>();
        List<Long> propagatingTimes = new ArrayList<>();
        List<Long> appendTimes = new ArrayList<>();
        try {
            for (int iteration = 0; iteration < WARMUP_PAIRS + SAMPLE_PAIRS; iteration++) {
                List<Long> baseline = iteration < WARMUP_PAIRS ? new ArrayList<>() : baselineTimes;
                List<Long> propagating =
                        iteration < WARMUP_PAIRS ? new ArrayList<>() : propagatingTimes;
                List<Long> appends = iteration < WARMUP_PAIRS ? new ArrayList<>() : appendTimes;
                if (iteration % 2 == 0) {
                    measure(writer, false, measured, baseline, appends);
                    measure(writer, true, measured, propagating, appends);
                } else {
                    measure(writer, true, measured, propagating, appends);
                    measure(writer, false, measured, baseline, appends);
                }
            }
            assertThat(queryInt("SELECT count(*) FROM outbox.outbox_event"))
                    .isEqualTo(WARMUP_PAIRS + SAMPLE_PAIRS);
            assertThat(queryInt("SELECT count(*) FROM delivery.outbox_atomicity_probe"))
                    .isEqualTo(2 * (WARMUP_PAIRS + SAMPLE_PAIRS));
            Measurement report =
                    new Measurement(
                            WARMUP_PAIRS,
                            SAMPLE_PAIRS,
                            percentile95(baselineTimes),
                            percentile95(propagatingTimes),
                            percentile95(appendTimes),
                            1,
                            1,
                            1,
                            0,
                            "Local sequential PostgreSQL reference operation; no approved latency threshold in P1.8; delivery answer handler is not implemented");
            Path output = Path.of("build/reports/outbox-writer-cost.json");
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } finally {
            pool.disposeLater().block(Duration.ofSeconds(10));
        }
    }

    private void measure(
            ReactiveOutboxWriter writer,
            boolean propagates,
            CountingConnections measured,
            List<Long> transactionTimes,
            List<Long> appendTimes) {
        String eventId =
                "01950f47-6000-7000-8000-%012d".formatted(measured.sequence.incrementAndGet());
        OutboxMessage message =
                new OutboxMessage(
                        OutboxEventId.parse(eventId),
                        "platform.ReferenceEvent.v1",
                        new AggregateReference("Reference", eventId),
                        new ReferenceEvent("platform.ReferenceEvent.v1", eventId, 1),
                        CORRELATION,
                        Instant.parse("2026-10-01T12:00:00Z"));
        int connectionsBefore = measured.acquisitions.get();
        int insertsBefore = measured.outboxInserts.get();
        Mono<Void> append =
                propagates
                        ? Mono.defer(
                                () -> {
                                    long started = System.nanoTime();
                                    return Mono.from(writer.append(TENANT, ACTOR, message))
                                            .then(
                                                    Mono.fromRunnable(
                                                            () ->
                                                                    appendTimes.add(
                                                                            System.nanoTime()
                                                                                    - started)));
                                })
                        : Mono.empty();
        Mono<Void> work =
                installModuleContext(UUID.fromString(TENANT.toString()))
                        .then(
                                databaseClient
                                        .sql(
                                                """
                        INSERT INTO delivery.outbox_atomicity_probe (tenant_id, probe_id)
                        VALUES (:tenantId, :probeId)
                        """)
                                        .bind("tenantId", UUID.fromString(TENANT.toString()))
                                        .bind("probeId", UUID.fromString(eventId))
                                        .fetch()
                                        .rowsUpdated()
                                        .then())
                        .then(append);
        long started = System.nanoTime();
        transactions
                .transactional(work)
                .contextWrite(
                        context ->
                                context.put(ActorContext.class, ACTOR)
                                        .put(
                                                RequestContextPropagation.CORRELATION_ID_KEY,
                                                CORRELATION.toString()))
                .block(Duration.ofSeconds(10));
        transactionTimes.add(System.nanoTime() - started);
        assertThat(measured.acquisitions.get() - connectionsBefore).isEqualTo(1);
        assertThat(measured.outboxInserts.get() - insertsBefore).isEqualTo(propagates ? 1 : 0);
    }

    private static double percentile95(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000.0;
    }

    private record Measurement(
            int warmupPairs,
            int samplePairs,
            double baselineP95Ms,
            double propagatingP95Ms,
            double appendP95Ms,
            int baselineConnectionAcquisitions,
            int propagatingConnectionAcquisitions,
            int propagatingOutboxInserts,
            int baselineOutboxInserts,
            String scope) {}

    private static final class CountingConnections implements ConnectionFactory {
        private final ConnectionFactory delegate;
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger outboxInserts = new AtomicInteger();
        private final AtomicInteger sequence = new AtomicInteger();

        private CountingConnections(ConnectionFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<? extends Connection> create() {
            return Mono.defer(
                    () ->
                            Mono.from(delegate.create())
                                    .map(
                                            connection -> {
                                                acquisitions.incrementAndGet();
                                                Connection observed =
                                                        mock(
                                                                Connection.class,
                                                                withSettings()
                                                                        .defaultAnswer(
                                                                                delegatesTo(
                                                                                        connection)));
                                                doAnswer(
                                                                invocation -> {
                                                                    String sql =
                                                                            invocation.getArgument(
                                                                                    0);
                                                                    if (sql.stripLeading()
                                                                            .startsWith(
                                                                                    "INSERT INTO outbox.outbox_event")) {
                                                                        outboxInserts
                                                                                .incrementAndGet();
                                                                    }
                                                                    return connection
                                                                            .createStatement(sql);
                                                                })
                                                        .when(observed)
                                                        .createStatement(anyString());
                                                return observed;
                                            }));
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return delegate.getMetadata();
        }
    }
}
