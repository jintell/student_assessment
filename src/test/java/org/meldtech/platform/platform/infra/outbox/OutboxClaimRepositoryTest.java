package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import reactor.test.StepVerifier;

class OutboxClaimRepositoryTest {

    @Test
    void claimIsBoundedOldestFirstAndNonBlocking() {
        String sql = OutboxClaimRepository.CLAIM_SQL;

        assertThat(sql).contains("ORDER BY created_at, outbox_event_id");
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).contains("LIMIT :batchSize");
        assertThat(sql).contains("SET state = 'CLAIMED'");
    }

    @Test
    void claimValidatesItsBoundsAndRequiresAReactiveTransaction() {
        OutboxClaimRepository repository =
                new OutboxClaimRepository(DatabaseClient.create(new NoOpConnectionFactory()));
        Instant expiry = Instant.parse("2026-09-03T12:00:30Z");

        StepVerifier.create(repository.claim(0, "relay-a", expiry))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(repository.claim(1001, "relay-a", expiry))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(repository.claim(10, " ", expiry))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(repository.claim(10, "relay-a", expiry))
                .expectError(NoTransactionException.class)
                .verify();
    }
}
