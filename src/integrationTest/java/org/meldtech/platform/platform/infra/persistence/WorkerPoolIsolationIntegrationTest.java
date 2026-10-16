package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.observability.DatabaseQueryTelemetry;
import org.meldtech.platform.platform.infra.observability.TracingConnectionFactory;
import org.meldtech.platform.platform.infra.outbox.OutboxPostgreSqlIntegrationTestSupport;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkerPoolIsolationIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void twoHeldLockConnectionsLeaveAllGradingAndBackgroundConnectionsAvailable() throws Exception {
        String password = UUID.randomUUID().toString();
        try (var connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_worker PASSWORD '%s'".formatted(password));
        }
        String url =
                "r2dbc:postgresql://%s:%d/%s"
                        .formatted(
                                postgres.getHost(),
                                postgres.getMappedPort(5432),
                                postgres.getDatabaseName());
        var properties =
                new WorkloadDatabaseProperties(
                        Map.of(
                                "worker", poolProperties(url, password, 4),
                                "grading", poolProperties(url, password, 4),
                                "sweep-lock", poolProperties(url, password, 2)));
        var configuration = new WorkloadConnectionPoolConfiguration();
        var metrics = new SimpleMeterRegistry();
        try {
            ConnectionPool locks =
                    physicalPool(
                            configuration.sweepLockConnectionFactory(
                                    properties,
                                    metrics,
                                    OpenTelemetry.noop(),
                                    DatabaseQueryTelemetry.NOOP,
                                    java.time.Instant::now));
            ConnectionPool grading =
                    physicalPool(
                            configuration.gradingConnectionFactory(
                                    properties,
                                    metrics,
                                    OpenTelemetry.noop(),
                                    DatabaseQueryTelemetry.NOOP,
                                    java.time.Instant::now));
            ConnectionPool background =
                    physicalPool(
                            configuration.workerConnectionFactory(
                                    properties,
                                    metrics,
                                    OpenTelemetry.noop(),
                                    DatabaseQueryTelemetry.NOOP,
                                    java.time.Instant::now));
            List<Connection> held = new ArrayList<>();
            try {
                for (int index = 0; index < 2; index++) {
                    Connection connection = Objects.requireNonNull(locks.create().block(TIMEOUT));
                    held.add(connection);
                    Boolean acquired =
                            Flux.from(
                                            connection
                                                    .createStatement(
                                                            "SELECT pg_try_advisory_lock($1) AS acquired")
                                                    .bind(0, 900100L + index)
                                                    .execute())
                                    .flatMap(
                                            result ->
                                                    result.map(
                                                            (row, metadata) ->
                                                                    row.get(
                                                                            "acquired",
                                                                            Boolean.class)))
                                    .single()
                                    .block(TIMEOUT);
                    assertThat(acquired).isTrue();
                }
                StepVerifier.create(locks.create())
                        .expectSubscription()
                        .expectNoEvent(Duration.ofMillis(100))
                        .thenCancel()
                        .verify(TIMEOUT);
                for (ConnectionPool pool : List.of(grading, background)) {
                    for (int index = 0; index < 4; index++) {
                        held.add(Objects.requireNonNull(pool.create().block(TIMEOUT)));
                    }
                }
                var backendIds = new HashSet<Integer>();
                for (Connection connection : held) {
                    backendIds.add(
                            Flux.from(
                                            connection
                                                    .createStatement(
                                                            "SELECT pg_backend_pid() AS pid")
                                                    .execute())
                                    .flatMap(
                                            result ->
                                                    result.map(
                                                            (row, metadata) ->
                                                                    row.get("pid", Integer.class)))
                                    .single()
                                    .block(TIMEOUT));
                }
                assertThat(backendIds).hasSize(10);
                assertThat(locks.getMetrics().orElseThrow().acquiredSize()).isEqualTo(2);
                assertThat(grading.getMetrics().orElseThrow().acquiredSize()).isEqualTo(4);
                assertThat(background.getMetrics().orElseThrow().acquiredSize()).isEqualTo(4);
            } finally {
                for (Connection connection : held) {
                    Flux.from(
                                    connection
                                            .createStatement("SELECT pg_advisory_unlock_all()")
                                            .execute())
                            .flatMap(result -> result.getRowsUpdated())
                            .then()
                            .block(TIMEOUT);
                    Mono.from(connection.close()).block(TIMEOUT);
                }
                locks.dispose();
                grading.dispose();
                background.dispose();
            }
        } finally {
            metrics.close();
        }
    }

    private static WorkloadDatabaseProperties.PoolProperties poolProperties(
            String url, String password, int size) {
        return new WorkloadDatabaseProperties.PoolProperties(
                url, "app_worker", password, 0, size, Duration.ofMinutes(30));
    }

    // Inspect only the physical allocation here; context/lock adapters are verified separately.
    private static ConnectionPool physicalPool(ConnectionFactory factory) {
        return (ConnectionPool)
                ((SecurityContextInitializer) ((TracingConnectionFactory) factory).delegate())
                        .delegate();
    }
}
