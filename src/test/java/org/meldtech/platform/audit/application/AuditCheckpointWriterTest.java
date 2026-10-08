package org.meldtech.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditCheckpoint;
import org.meldtech.platform.audit.domain.AuditCheckpointPolicy;
import org.meldtech.platform.audit.domain.AuditCheckpointTail;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AuditCheckpointWriterTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final AuditChainKey KEY =
            new AuditChainKey(
                    TenantId.parse("01991a95-df27-7000-8000-000000000001"),
                    new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 9)),
                    3,
                    64);

    @Test
    void writesAtTheRecordThreshold() {
        RecordingRepository repository =
                new RecordingRepository(tail(10_000, NOW.minusSeconds(30)));
        AuditCheckpointWriter writer = writer(repository);

        StepVerifier.create(writer.writeIfDue(KEY, NOW, false))
                .expectNext(AuditCheckpointWriter.WriteResult.WRITTEN)
                .verifyComplete();

        assertThat(repository.inserts).hasValue(1);
    }

    @Test
    void writesWhenTheOldestRecordIsOneHourOld() {
        RecordingRepository repository = new RecordingRepository(tail(1, NOW.minusSeconds(3_600)));

        StepVerifier.create(writer(repository).writeIfDue(KEY, NOW, false))
                .expectNext(AuditCheckpointWriter.WriteResult.WRITTEN)
                .verifyComplete();
    }

    @Test
    void writesANonEmptyTerminalCheckpointAtEpochBoundary() {
        RecordingRepository repository = new RecordingRepository(tail(3, NOW.minusSeconds(1)));

        StepVerifier.create(writer(repository).writeIfDue(KEY, NOW, true))
                .expectNext(AuditCheckpointWriter.WriteResult.WRITTEN)
                .verifyComplete();
    }

    @Test
    void doesNotCheckpointAnEmptyShardOrAYoungShortTail() {
        RecordingRepository empty = new RecordingRepository(emptyTail());
        RecordingRepository young = new RecordingRepository(tail(9_999, NOW.minusSeconds(3_599)));

        StepVerifier.create(writer(empty).writeIfDue(KEY, NOW, true))
                .expectNext(AuditCheckpointWriter.WriteResult.NOT_DUE)
                .verifyComplete();
        StepVerifier.create(writer(young).writeIfDue(KEY, NOW, false))
                .expectNext(AuditCheckpointWriter.WriteResult.NOT_DUE)
                .verifyComplete();

        assertThat(empty.inserts).hasValue(0);
        assertThat(young.inserts).hasValue(0);
    }

    private static AuditCheckpointWriter writer(RecordingRepository repository) {
        AuditEvidenceSigner signer =
                message ->
                        Mono.just(
                                new AuditSignature(
                                        "key-v3",
                                        "RSASSA_PSS_SHA_256",
                                        new byte[] {1},
                                        "kms-request",
                                        NOW));
        return new AuditCheckpointWriter(
                repository, signer, new AuditCheckpointPolicy(), new CanonicalJsonCodec());
    }

    private static AuditCheckpointTail tail(long records, Instant oldest) {
        return new AuditCheckpointTail(
                KEY,
                0,
                records,
                new AuditHash((short) 1, new byte[32]),
                Optional.of(oldest),
                Optional.of(NOW));
    }

    private static AuditCheckpointTail emptyTail() {
        return new AuditCheckpointTail(
                KEY,
                0,
                0,
                new AuditHash((short) 1, new byte[32]),
                Optional.empty(),
                Optional.empty());
    }

    private static final class RecordingRepository implements AuditCheckpointRepository {

        private final AuditCheckpointTail tail;
        private final AtomicInteger inserts = new AtomicInteger();

        private RecordingRepository(AuditCheckpointTail tail) {
            this.tail = tail;
        }

        @Override
        public Mono<AuditCheckpointTail> loadVerifiedTail(AuditChainKey chainKey) {
            return Mono.just(tail);
        }

        @Override
        public Mono<Instant> trustedSigningTime() {
            return Mono.just(NOW);
        }

        @Override
        public Mono<Boolean> insert(AuditCheckpoint checkpoint) {
            inserts.incrementAndGet();
            return Mono.just(true);
        }
    }
}
