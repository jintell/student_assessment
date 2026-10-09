package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void abortsWithoutRunningAnyLaterStep(int failedStep) {
        RecordingOperations operations = new RecordingOperations(failedStep);

        StepVerifier.create(new AuditDispositionExecutor(operations).execute(request()))
                .expectErrorMessage("step " + failedStep + " failed")
                .verify();

        assertThat(operations.actions).containsExactlyElementsOf(prefixThrough(failedStep, false));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void cannotSkipAnUncompletedRequiredStep(int waitingStep) {
        Sinks.Empty<Void> gate = Sinks.empty();
        RecordingOperations operations =
                new RecordingOperations(-1, waitingStep, gate.asMono(), -1);

        StepVerifier.create(new AuditDispositionExecutor(operations).execute(request()))
                .then(
                        () -> {
                            assertThat(operations.actions)
                                    .containsExactlyElementsOf(prefixThrough(waitingStep, false));
                            assertThat(
                                            gate.tryEmitError(
                                                    new IllegalStateException(
                                                            "required step not completed")))
                                    .isEqualTo(Sinks.EmitResult.OK);
                        })
                .expectErrorMessage("required step not completed")
                .verify();

        assertThat(operations.actions).containsExactlyElementsOf(prefixThrough(waitingStep, false));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void cannotAdvanceWithoutRecordingStepCompletion(int failedProgress) {
        RecordingOperations operations =
                new RecordingOperations(-1, -1, Mono.empty(), failedProgress);

        StepVerifier.create(new AuditDispositionExecutor(operations).execute(request()))
                .expectErrorMessage("progress " + failedProgress + " failed")
                .verify();

        assertThat(operations.actions)
                .containsExactlyElementsOf(prefixThrough(failedProgress, true));
    }

    private static List<String> prefixThrough(int step, boolean includesProgress) {
        List<String> expected = new ArrayList<>();
        for (int number = 1; number < step; number++) {
            expected.add(Integer.toString(number));
            expected.add("progress-" + number);
        }
        expected.add(Integer.toString(step));
        if (includesProgress) {
            expected.add("progress-" + step);
        }
        return expected;
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
        private final int waitingStep;
        private final Mono<Void> completion;
        private final int failedProgress;
        private final List<String> actions = new CopyOnWriteArrayList<>();

        private RecordingOperations(int failedStep) {
            this(failedStep, -1, Mono.empty(), -1);
        }

        private RecordingOperations(
                int failedStep, int waitingStep, Mono<Void> completion, int failedProgress) {
            this.failedStep = failedStep;
            this.waitingStep = waitingStep;
            this.completion = completion;
            this.failedProgress = failedProgress;
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
            return number == failedProgress
                    ? Mono.error(new IllegalStateException("progress " + number + " failed"))
                    : Mono.empty();
        }

        private Mono<Void> step(int number) {
            actions.add(Integer.toString(number));
            if (number == waitingStep) {
                return completion;
            }
            return number == failedStep
                    ? Mono.error(new IllegalStateException("step " + number + " failed"))
                    : Mono.empty();
        }
    }
}
