package org.meldtech.platform.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AtomicCrossModuleFlowTest {

    @Test
    void containsOnlyTheRatifiedExamEntryPair() {
        assertThat(AtomicCrossModuleFlow.values())
                .containsExactly(AtomicCrossModuleFlow.EXAM_ENTRY);
        assertThat(AtomicCrossModuleFlow.EXAM_ENTRY.flow())
                .isEqualTo("examaccess.verifyPinAndStartAttempt");
        assertThat(AtomicCrossModuleFlow.EXAM_ENTRY.compositeRole()).isEqualTo("app_txn_examentry");
    }
}
