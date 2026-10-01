package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxClaimRepositoryTest {

    @Test
    void claimIsBoundedOldestFirstAndNonBlocking() {
        String sql = OutboxClaimRepository.CLAIM_SQL;

        assertThat(sql).contains("ORDER BY created_at, outbox_event_id");
        assertThat(sql).contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql).contains("LIMIT :batchSize");
        assertThat(sql).contains("SET state = 'CLAIMED'");
    }
}
