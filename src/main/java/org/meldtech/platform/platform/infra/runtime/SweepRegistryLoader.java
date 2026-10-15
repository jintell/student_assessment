package org.meldtech.platform.platform.infra.runtime;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepDefinition;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.infra.persistence.DatabaseSecurityScopes;
import org.meldtech.platform.platform.infra.persistence.DiscardableConnection;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Loads and validates the worker's registry before Spring starts lifecycle participants. */
@Component
@RoleScoped("worker")
public final class SweepRegistryLoader implements SmartInitializingSingleton {

    static final String READ_SQL =
            """
            SELECT sweep_name, lock_key, owning_module, owning_feature,
                   default_interval_ms, enabled, system_actor
            FROM platform.sweep_registry ORDER BY sweep_name
            """;
    private static final Set<String> MODULES =
            Set.of(
                    "tenancy",
                    "iam",
                    "academic",
                    "people",
                    "questionbank",
                    "authoring",
                    "examaccess",
                    "delivery",
                    "grading",
                    "result",
                    "correction",
                    "notification",
                    "platform",
                    "audit",
                    "outbox");
    private static final Map<String, Long> FIXED_INTERVALS =
            Map.of("auto-submit", 10000L, "stale-claim-reclaim", 30000L, "outbox-relay", 200L);
    private static final Duration MINIMUM_INTERVAL = Duration.ofMillis(100);
    private static final Duration MAXIMUM_INTERVAL = Duration.ofDays(1);
    private final ConnectionFactory connections;
    private final List<SweepDefinition> definitions;
    private volatile List<RegisteredSweep> enabled = List.of();
    private volatile boolean loaded;

    public SweepRegistryLoader(
            @Qualifier("workerConnectionFactory") ConnectionFactory connections,
            List<SweepDefinition> definitions) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.definitions = List.copyOf(definitions);
    }

    public Mono<List<RegistryRow>> load() {
        return Mono.usingWhen(
                        Mono.from(connections.create()),
                        connection ->
                                DatabaseSecurityScopes.inSweepCoordinationTransaction(
                                        connection,
                                        Flux.defer(
                                                        () ->
                                                                Flux.from(
                                                                        connection
                                                                                .createStatement(
                                                                                        READ_SQL)
                                                                                .execute()))
                                                .concatMap(
                                                        result ->
                                                                result.map(
                                                                        (row, metadata) ->
                                                                                readRow(row)))
                                                .collectList()),
                        connection -> Mono.from(connection.close()),
                        (connection, failure) -> discard(connection),
                        SweepRegistryLoader::discard)
                .timeout(Duration.ofSeconds(10));
    }

    @Override
    public void afterSingletonsInstantiated() {
        loaded = false;
        enabled = List.of();
        // This bounded wait runs on Spring's bootstrap thread, never on a request event loop.
        var loading = load().map(rows -> validate(rows, definitions)).toFuture();
        try {
            enabled = loading.get();
            loaded = true;
        } catch (InterruptedException exception) {
            loading.cancel(true);
            Thread.currentThread().interrupt();
            throw invalid("startup interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RegistryValidationException validation) {
                throw validation;
            }
            throw invalid("registry load or validation failed");
        }
    }

    public List<RegisteredSweep> enabledSweeps() {
        if (!loaded) {
            throw invalid("registry has not passed startup validation");
        }
        return enabled;
    }

    static List<RegisteredSweep> validate(
            List<RegistryRow> rows, List<SweepDefinition> definitions) {
        Map<String, SweepDefinition> declared = new HashMap<>();
        for (SweepDefinition definition : definitions) {
            SweepMetadata metadata = definition.metadata();
            if (declared.putIfAbsent(metadata.name(), definition) != null) {
                throw invalid("duplicate sweep definition");
            }
            if (metadata.maximumBatchDuration().compareTo(Duration.ofSeconds(4)) > 0
                    || metadata.maximumTickDuration().compareTo(Duration.ofSeconds(300)) > 0) {
                throw invalid("batch or tick duration exceeds runtime bounds");
            }
            if (metadata.minimumInterval().compareTo(MINIMUM_INTERVAL) < 0
                    || metadata.maximumInterval().compareTo(MAXIMUM_INTERVAL) > 0) {
                throw invalid("declared interval bounds exceed runtime bounds");
            }
        }
        Set<String> names = new java.util.HashSet<>();
        Map<Long, String> lockOwners = new HashMap<>();
        for (RegistryRow row : rows) {
            validateRow(row);
            if (!names.add(row.name())) {
                throw invalid("duplicate registry name");
            }
            String previous = lockOwners.putIfAbsent(row.lockKey(), row.name());
            if (previous != null) {
                throw invalid("lock-key collision between " + previous + " and " + row.name());
            }
        }
        List<RegisteredSweep> enabled = new ArrayList<>();
        for (RegistryRow row : rows) {
            SweepDefinition definition = declared.get(row.name());
            if (definition == null) {
                if (row.enabled()) {
                    throw invalid("enabled registry row has no definition");
                }
                continue;
            }
            SweepMetadata metadata = definition.metadata();
            Duration interval = Duration.ofMillis(row.intervalMillis());
            if (!row.owningModule().equals(metadata.owningModule())
                    || !row.owningFeature().equals(metadata.owningFeature())
                    || !row.systemActorName().equals(metadata.systemActor().name())
                    || !interval.equals(metadata.defaultInterval())
                    || interval.compareTo(metadata.minimumInterval()) < 0
                    || interval.compareTo(metadata.maximumInterval()) > 0) {
                throw invalid("registry and declaration metadata disagree");
            }
            if (row.enabled()) {
                enabled.add(new RegisteredSweep(definition, interval));
            }
        }
        if (!names.containsAll(declared.keySet())) {
            List<String> missing =
                    declared.keySet().stream()
                            .filter(name -> !names.contains(name))
                            .sorted()
                            .toList();
            throw invalid("sweep definition has no registry row: " + missing);
        }
        return List.copyOf(enabled);
    }

    private static void validateRow(RegistryRow row) {
        if (Arrays.stream(SystemActor.values())
                .noneMatch(actor -> actor.name().equals(row.systemActorName()))) {
            throw invalid("unknown system actor");
        }
        if (!row.name().matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
                || row.name().length() > 96
                || !MODULES.contains(row.owningModule())
                || !row.owningFeature().matches("FEAT-[A-Z]+-[0-9]{3}")
                || row.owningFeature().length() > 32
                || row.intervalMillis() < 100
                || row.intervalMillis() > 86_400_000) {
            throw invalid("invalid registry identity or interval");
        }
        Long fixedInterval = FIXED_INTERVALS.get(row.name());
        if (fixedInterval != null && fixedInterval.longValue() != row.intervalMillis()) {
            throw invalid("fixed sweep cadence has changed");
        }
    }

    private static RegistryRow readRow(Row row) {
        return new RegistryRow(
                required(row, "sweep_name", String.class),
                required(row, "lock_key", Long.class),
                required(row, "owning_module", String.class),
                required(row, "owning_feature", String.class),
                required(row, "default_interval_ms", Long.class),
                required(row, "enabled", Boolean.class),
                required(row, "system_actor", String.class));
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        return Objects.requireNonNull(row.get(column, type), column);
    }

    private static Mono<Void> discard(Connection connection) {
        return connection instanceof DiscardableConnection discardable
                ? discardable.discard()
                : Mono.from(connection.close());
    }

    private static RegistryValidationException invalid(String reason) {
        return new RegistryValidationException(reason);
    }

    private static final class RegistryValidationException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private RegistryValidationException(String reason) {
            super("SWEEP_REGISTRY_INVALID: " + reason);
        }
    }

    public record RegisteredSweep(SweepDefinition definition, Duration interval) {}

    public record RegistryRow(
            String name,
            long lockKey,
            String owningModule,
            String owningFeature,
            long intervalMillis,
            boolean enabled,
            String systemActorName) {
        public RegistryRow {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(owningModule, "owningModule");
            Objects.requireNonNull(owningFeature, "owningFeature");
            Objects.requireNonNull(systemActorName, "systemActorName");
        }
    }
}
