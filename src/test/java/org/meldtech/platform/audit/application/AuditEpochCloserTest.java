package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditEpochCloserTest {

    @Test
    void sealsDistinctEpochsSeriallyInCanonicalOrder() {
        TenantId tenant = TenantId.parse("01991a95-df27-7000-8000-000000000001");
        EpochIdentity augustGeneral = epoch(2026, 8, RetentionClass.GENERAL_AUDIT_EVENT);
        EpochIdentity augustCorrection = epoch(2026, 8, RetentionClass.RESULT_CORRECTION_EVIDENCE);
        EpochIdentity julyPin = epoch(2026, 7, RetentionClass.PIN_SECURITY_EVENT);
        List<EpochIdentity> invocations = new CopyOnWriteArrayList<>();
        AuditEpochCloser closer =
                new AuditEpochCloser(
                        (ignored, epoch) -> {
                            invocations.add(epoch);
                            return Mono.just(AuditEpochSealer.SealResult.SEALED);
                        });

        StepVerifier.create(
                        closer.close(
                                tenant,
                                List.of(augustGeneral, julyPin, augustCorrection, augustGeneral)))
                .assertNext(
                        closed ->
                                assertThat(closed)
                                        .extracting(AuditEpochCloser.ClosedEpoch::epoch)
                                        .containsExactly(julyPin, augustCorrection, augustGeneral))
                .verifyComplete();

        assertThat(invocations).containsExactly(julyPin, augustCorrection, augustGeneral);
    }

    private static EpochIdentity epoch(int year, int month, RetentionClass retentionClass) {
        return new EpochIdentity(retentionClass, YearMonth.of(year, month));
    }
}
