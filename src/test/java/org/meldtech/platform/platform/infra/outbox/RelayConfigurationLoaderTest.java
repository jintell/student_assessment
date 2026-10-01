package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

class RelayConfigurationLoaderTest {

    @Test
    void validReloadTakesEffectWithoutRestart() {
        RelayConfigurationLoader loader = loader();

        RelayConfiguration loaded = loader.accept(rows(400, 500));

        assertThat(loaded.batchSize()).isEqualTo(400);
        assertThat(loaded.tickInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(loader.current()).isSameAs(loaded);
    }

    @Test
    void invalidReloadRetainsPreviousConfiguration() {
        RelayConfigurationLoader loader = loader();
        RelayConfiguration previous = loader.accept(rows(300, 300));

        assertThatIllegalStateException()
                .isThrownBy(() -> loader.accept(rows(1001, 300)))
                .withMessageStartingWith("OUTBOX_RELAY_CONFIGURATION_INVALID");
        assertThat(loader.current()).isSameAs(previous);
    }

    private static RelayConfigurationLoader loader() {
        return new RelayConfigurationLoader(DatabaseClient.create(new NoOpConnectionFactory()));
    }

    private static List<RelayConfigurationLoader.ConfigRow> rows(int batchSize, int tickMs) {
        return List.of(
                new RelayConfigurationLoader.ConfigRow(
                        RelayConfigurationLoader.BATCH_SIZE_KEY, batchSize, 1, 1000, 1),
                new RelayConfigurationLoader.ConfigRow(
                        RelayConfigurationLoader.TICK_INTERVAL_KEY, tickMs, 50, 5000, 1));
    }
}
