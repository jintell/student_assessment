package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StaleClaimReclaimerTest {

    @Test
    void expiredClaimsReturnToPendingAndClearOwnership() {
        assertThat(StaleClaimReclaimer.RECLAIM_SQL)
                .contains("state = 'PENDING'")
                .contains("claim_expires_at = NULL")
                .contains("claimed_by = NULL")
                .contains("claim_expires_at < CURRENT_TIMESTAMP");
    }
}
