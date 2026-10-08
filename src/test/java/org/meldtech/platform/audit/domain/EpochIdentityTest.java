package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;

class EpochIdentityTest {

    @Test
    void derivesThePeriodFromUtc() {
        EpochIdentity epoch =
                EpochIdentity.from(
                        RetentionClass.GENERAL_AUDIT_EVENT, Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(epoch.period()).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void ordersPeriodBeforeTheApprovedRetentionClassOrder() {
        List<EpochIdentity> epochs =
                new ArrayList<>(
                        List.of(
                                new EpochIdentity(
                                        RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 10)),
                                new EpochIdentity(
                                        RetentionClass.PIN_SECURITY_EVENT, YearMonth.of(2026, 9)),
                                new EpochIdentity(
                                        RetentionClass.RESULT_CORRECTION_EVIDENCE,
                                        YearMonth.of(2026, 10))));

        epochs.sort(EpochIdentity.CANONICAL_ORDER);

        assertThat(epochs)
                .extracting(EpochIdentity::retentionClass)
                .containsExactly(
                        RetentionClass.PIN_SECURITY_EVENT,
                        RetentionClass.RESULT_CORRECTION_EVIDENCE,
                        RetentionClass.GENERAL_AUDIT_EVENT);
    }
}
