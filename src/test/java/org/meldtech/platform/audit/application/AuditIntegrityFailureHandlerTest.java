package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditVerificationMismatch;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditIntegrityFailureHandlerTest {

    @Test
    void preservesRaisesP1HaltsAndPropagatesWithoutARepairApi() {
        List<String> actions = new CopyOnWriteArrayList<>();
        AuditEvidencePreservation preservation =
                new AuditEvidencePreservation() {
                    @Override
                    public Mono<Void> preserve(AuditVerificationFinding finding) {
                        actions.add("preserve");
                        return Mono.empty();
                    }

                    @Override
                    public Mono<Void> recordHighSeverityMetric(AuditVerificationFinding finding) {
                        actions.add("metric");
                        return Mono.empty();
                    }

                    @Override
                    public Mono<Void> raiseP1Alert(AuditVerificationFinding finding) {
                        actions.add("p1");
                        return Mono.empty();
                    }

                    @Override
                    public Mono<Void> haltSealingAndDisposition(TenantId tenantId) {
                        actions.add("halt");
                        return Mono.empty();
                    }
                };
        AuditVerificationMismatch mismatch =
                new AuditVerificationMismatch("root does not reproduce");
        AuditVerificationFinding finding =
                new AuditVerificationFinding(
                        UUID.fromString("01991a95-df27-7000-8000-000000000099"),
                        TenantId.parse("01991a95-df27-7000-8000-000000000001"),
                        "ROOT_MISMATCH",
                        "snapshot-42",
                        "0/16B6C50",
                        List.of("GENERAL_AUDIT_EVENT:2026-08"),
                        Instant.parse("2026-09-03T12:00:00Z"));

        StepVerifier.create(
                        new AuditIntegrityFailureHandler(preservation)
                                .preserveAndHalt(finding, mismatch))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(mismatch))
                .verify();

        assertThat(actions).containsExactly("preserve", "metric", "p1", "halt");
        assertThat(List.of(AuditEvidencePreservation.class.getMethods()))
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.contains("repair") || name.contains("reconcile"));
    }
}
