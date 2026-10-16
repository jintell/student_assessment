package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RelayConfigurationIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    @Test
    void invalidDatabaseSnapshotRetainsLastValueAndValidReloadNeedsNoRestart() {
        RelayConfigurationLoader loader = new RelayConfigurationLoader(databaseClient);
        IllegalStateException rollback =
                new IllegalStateException("rollback configuration fixture");

        Mono<Void> verification =
                updateBatchSize(300, 1000)
                        .then(loader.load())
                        .flatMap(
                                previous -> {
                                    assertThat(previous.batchSize()).isEqualTo(300);
                                    return updateBatchSize(1001, 1001)
                                            .then(loader.load())
                                            .doOnNext(
                                                    unexpected -> {
                                                        throw new AssertionError(
                                                                "Out-of-range snapshot was accepted");
                                                    })
                                            .onErrorResume(
                                                    IllegalStateException.class,
                                                    failure -> {
                                                        assertThat(failure)
                                                                .hasMessageStartingWith(
                                                                        "OUTBOX_RELAY_CONFIGURATION_INVALID");
                                                        assertThat(loader.current())
                                                                .isSameAs(previous);
                                                        return Mono.empty();
                                                    });
                                })
                        .then(updateBatchSize(400, 1000))
                        .then(loader.load())
                        .doOnNext(
                                reloaded -> {
                                    assertThat(reloaded.batchSize()).isEqualTo(400);
                                    assertThat(loader.current()).isSameAs(reloaded);
                                })
                        .then(Mono.error(rollback));

        StepVerifier.create(transactions.transactional(verification))
                .expectErrorSatisfies(failure -> assertThat(failure).isSameAs(rollback))
                .verify();
    }

    private Mono<Void> updateBatchSize(int value, int maximum) {
        return databaseClient
                .sql(
                        """
                        UPDATE platform.platform_config
                        SET integer_value = :value, maximum_integer = :maximum,
                            revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE config_key = 'outbox.relay.batch-size'
                        """)
                .bind("value", value)
                .bind("maximum", maximum)
                .fetch()
                .rowsUpdated()
                .doOnNext(count -> assertThat(count).isEqualTo(1))
                .then();
    }
}
