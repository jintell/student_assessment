package org.meldtech.platform.platform.infra.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

final class RelayConfigurationLoader {

    static final String BATCH_SIZE_KEY = "outbox.relay.batch-size";
    static final String TICK_INTERVAL_KEY = "outbox.relay.tick-interval-ms";
    private static final String LOAD_SQL =
            """
            SELECT config_key, integer_value, minimum_integer, maximum_integer, revision
            FROM platform.platform_config
            WHERE config_key IN ('outbox.relay.batch-size', 'outbox.relay.tick-interval-ms')
            """;

    private final DatabaseClient databaseClient;
    private final AtomicReference<RelayConfiguration> current =
            new AtomicReference<>(RelayConfiguration.defaults());

    RelayConfigurationLoader(DatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
    }

    Mono<RelayConfiguration> load() {
        return databaseClient
                .sql(LOAD_SQL)
                .map(
                        (row, metadata) ->
                                new ConfigRow(
                                        Objects.requireNonNull(
                                                row.get("config_key", String.class), "config_key"),
                                        requiredInt(row.get("integer_value", Number.class)),
                                        requiredInt(row.get("minimum_integer", Number.class)),
                                        requiredInt(row.get("maximum_integer", Number.class)),
                                        Objects.requireNonNull(
                                                        row.get("revision", Number.class),
                                                        "revision")
                                                .longValue()))
                .all()
                .collectList()
                .map(this::accept);
    }

    RelayConfiguration current() {
        return Objects.requireNonNull(current.get(), "current relay configuration");
    }

    RelayConfiguration accept(List<ConfigRow> rows) {
        try {
            Map<String, ConfigRow> byKey =
                    rows.stream()
                            .collect(
                                    Collectors.toUnmodifiableMap(
                                            ConfigRow::key, Function.identity()));
            if (!byKey.keySet().equals(java.util.Set.of(BATCH_SIZE_KEY, TICK_INTERVAL_KEY))) {
                throw new IllegalArgumentException("Both relay configuration keys are required");
            }
            ConfigRow batchSize = Objects.requireNonNull(byKey.get(BATCH_SIZE_KEY), BATCH_SIZE_KEY);
            ConfigRow tickInterval =
                    Objects.requireNonNull(byKey.get(TICK_INTERVAL_KEY), TICK_INTERVAL_KEY);
            batchSize.validate(1, 1000);
            tickInterval.validate(50, 5000);
            RelayConfiguration candidate =
                    new RelayConfiguration(
                            batchSize.value(), Duration.ofMillis(tickInterval.value()));
            current.set(candidate);
            return candidate;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "OUTBOX_RELAY_CONFIGURATION_INVALID: previous configuration retained",
                    exception);
        }
    }

    private static int requiredInt(Number value) {
        return Objects.requireNonNull(value, "integer configuration value").intValue();
    }

    record ConfigRow(String key, int value, int minimum, int maximum, long revision) {

        ConfigRow {
            Objects.requireNonNull(key, "key");
            if (revision < 1) {
                throw new IllegalArgumentException("Configuration revision must be positive");
            }
        }

        void validate(int expectedMinimum, int expectedMaximum) {
            if (minimum != expectedMinimum
                    || maximum != expectedMaximum
                    || value < minimum
                    || value > maximum) {
                throw new IllegalArgumentException("Invalid bounded value for " + key);
            }
        }
    }
}
