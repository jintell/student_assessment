package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RelaySingletonTest {

    @Test
    void sweepKeyIsStableAndNamed() {
        assertThat(RelaySingleton.lockKey("outbox-relay"))
                .isEqualTo(RelaySingleton.lockKey("outbox-relay"))
                .isNotEqualTo(RelaySingleton.lockKey("retention-sweep"));
        assertThat(RelaySingleton.LOCK_SQL).contains("pg_try_advisory_xact_lock");
    }

    @Test
    void blankSweepNameIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> RelaySingleton.lockKey(""));
    }
}
