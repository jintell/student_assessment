package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditDispositionExecutorTest {

    @Test
    void executesAndRecordsAllFiveStepsInOrder() {
        RecordingOperations operations = new RecordingOperations(-1);

        StepVerifier.create(new AuditDispositionExecutor(operations).execute(request()))
                .assertNext(
                        result ->
                                assertThat(result.completedStep())
                                        .isEqualTo(
                                                AuditDispositionExecutor.Step
                                                        .RETAINED_EVIDENCE_VERIFIED))
                .verifyComplete();

        assertThat(operations.actions)
                .containsExactly(
                        "1", "progress-1",
                        "2", "progress-2",
                        "3", "progress-3",
                        "4", "progress-4",
                        "5", "progress-5");
    }

    @Test
    void abortsWithoutRunningAnyLaterStep() {
        RecordingOperations operations = new RecordingOperations(3);

        StepVerifier.create(new AuditDispositionExecutor(operations).execute(request()))
                .expectErrorMessage("step 3 failed")
                .verify();

        assertThat(operations.actions).containsExactly("1", "progress-1", "2", "progress-2", "3");
    }

    private static DispositionRequest request() {
        return new DispositionRequest(
                UUID.fromString("01991a95-df27-7000-8000-000000000099"),
                TenantId.parse("01991a95-df27-7000-8000-000000000001"),
                new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8)),
                "audit.general.v3",
                3,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                "approval-42");
    }

    private static final class RecordingOperations implements AuditDispositionOperations {

        private final int failedStep;
        private final List<String> actions = new CopyOnWriteArrayList<>();

        private RecordingOperations(int failedStep) {
            this.failedStep = failedStep;
        }

        @Override
        public Mono<Void> freezeAndVerifyShardEvidence(DispositionRequest request) {
            return step(1);
        }

        @Override
        public Mono<Void> verifySealInDenseRootChain(DispositionRequest request) {
            return step(2);
        }

        @Override
        public Mono<Void> emitAndVerifyDispositionEvidence(DispositionRequest request) {
            return step(3);
        }

        @Override
        public Mono<Void> recheckPolicyAndDisposePartition(DispositionRequest request) {
            return step(4);
        }

        @Override
        public Mono<Void> verifyRetainedEvidence(DispositionRequest request) {
            return step(5);
        }

        @Override
        public Mono<Void> recordProgress(
                DispositionRequest request, AuditDispositionExecutor.Step completedStep) {
            int number =
                    switch (completedStep) {
                        case SHARD_EVIDENCE_VERIFIED -> 1;
                        case SEAL_AND_ROOT_VERIFIED -> 2;
                        case DISPOSITION_EVIDENCE_COMMITTED -> 3;
                        case PARTITION_DISPOSED -> 4;
                        case RETAINED_EVIDENCE_VERIFIED -> 5;
                    };
            actions.add("progress-" + number);
            return Mono.empty();
        }

        private Mono<Void> step(int number) {
            actions.add(Integer.toString(number));
            return number == failedStep
                    ? Mono.error(new IllegalStateException("step " + number + " failed"))
                    : Mono.empty();
        }
    }
}
