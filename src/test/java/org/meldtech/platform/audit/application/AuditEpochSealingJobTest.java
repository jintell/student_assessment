package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditEpochSealingJobTest {

    private static final TenantId TENANT = TenantId.parse("01991a95-df27-7000-8000-000000000001");
    private static final EpochIdentity EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8));

    @Test
    void defersAndAlertsWhenKmsIsUnavailable() {
        AtomicInteger alerts = new AtomicInteger();
        AuditEpochSealingJob job =
                new AuditEpochSealingJob(
                        (tenant, epoch) -> Mono.error(new IllegalStateException("kms unavailable")),
                        (tenant, epoch, reason) -> {
                            assertThat(reason).isEqualTo("AUDIT_SEAL_UNAVAILABLE");
                            alerts.incrementAndGet();
                        });

        StepVerifier.create(job.run(TENANT, EPOCH))
                .expectNext(AuditEpochSealingJob.JobResult.DEFERRED)
                .verifyComplete();

        assertThat(alerts).hasValue(1);
    }

    @Test
    void unsealedEpochIsNotEligibleForDisposition() {
        AuditEpochDisposability disposability =
                new AuditEpochDisposability((tenant, epoch) -> Mono.just(false));

        StepVerifier.create(disposability.hasRequiredSeal(TENANT, EPOCH))
                .expectNext(false)
                .verifyComplete();
    }
}
