package org.meldtech.platform.platform.infra.persistence;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.meldtech.platform.platform.infra.observability.DatabaseQueryTelemetry;
import org.meldtech.platform.platform.infra.observability.TracingConnectionFactory;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Test wiring uses the production worker and coordination pools, including security wrappers. */
public final class ReferenceSweepPools implements AutoCloseable {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ConnectionFactory worker;
    private final ConnectionFactory locks;

    public ReferenceSweepPools(PostgreSQLContainer postgres) throws SQLException {
        String password = UUID.randomUUID().toString();
        try (var owner =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = owner.createStatement()) {
            statement.execute("ALTER ROLE app_worker PASSWORD '" + password + "'");
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
                                "worker",
                                pool(url, password, 4),
                                "grading",
                                pool(url, password, 4),
                                "sweep-lock",
                                pool(url, password, 2)));
        var configuration = new WorkloadConnectionPoolConfiguration();
        worker =
                configuration.workerConnectionFactory(
                        properties,
                        meters,
                        OpenTelemetry.noop(),
                        DatabaseQueryTelemetry.NOOP,
                        Instant::now);
        locks =
                configuration.sweepLockConnectionFactory(
                        properties,
                        meters,
                        OpenTelemetry.noop(),
                        DatabaseQueryTelemetry.NOOP,
                        Instant::now);
    }

    public ConnectionFactory worker() {
        return worker;
    }

    public ConnectionFactory locks() {
        return locks;
    }

    public SweepTransactions transactions() {
        return new DefaultSweepTransactions(worker, new RequestContextPropagation());
    }

    public int acquiredConnections() {
        return physical(worker).getMetrics().orElseThrow().acquiredSize()
                + physical(locks).getMetrics().orElseThrow().acquiredSize();
    }

    @Override
    public void close() {
        physical(worker).dispose();
        physical(locks).dispose();
        meters.close();
    }

    private static WorkloadDatabaseProperties.PoolProperties pool(
            String url, String password, int size) {
        return new WorkloadDatabaseProperties.PoolProperties(
                url, "app_worker", password, 0, size, Duration.ofMinutes(1));
    }

    private static ConnectionPool physical(ConnectionFactory factory) {
        var tracing = (TracingConnectionFactory) factory;
        var security = (SecurityContextInitializer) tracing.delegate();
        return (ConnectionPool) security.delegate();
    }
}
