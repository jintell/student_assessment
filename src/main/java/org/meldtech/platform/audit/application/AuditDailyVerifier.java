package org.meldtech.platform.audit.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditChainWalk;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditVerificationMismatch;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochRootDerivation;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class AuditDailyVerifier {

    private final AuditVerificationEvidence evidence;
    private final AuditSignatureVerifier signatures;
    private final EpochRootDerivation roots;
    private final CanonicalJsonCodec codec;

    public AuditDailyVerifier(
            AuditVerificationEvidence evidence,
            AuditSignatureVerifier signatures,
            EpochRootDerivation roots,
            CanonicalJsonCodec codec) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.roots = Objects.requireNonNull(roots, "roots");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Mono<VerificationSummary> verify() {
        Mono<Long> openCount =
                evidence.openChains()
                        .concatMap(chain -> verifyOpenChain(chain).thenReturn(chain))
                        .count();
        Mono<Long> sealedCount =
                evidence.sealedEpochs()
                        .concatMap(seal -> verifySeal(seal).thenReturn(seal))
                        .count();
        return Mono.zip(openCount, sealedCount)
                .map(counts -> new VerificationSummary(counts.getT1(), counts.getT2()));
    }

    private Mono<Void> verifyOpenChain(OpenAuditChain chain) {
        Map<Long, AuditHash> checkpointHeads = new HashMap<>();
        return evidence.records(chain)
                .scan(
                        AuditChainWalk.begin(chain.seed()),
                        (state, record) -> {
                            AuditChainWalk.State next = AuditChainWalk.append(state, record);
                            checkpointHeads.put(record.sequence(), record.recordHash());
                            return next;
                        })
                .last()
                .doOnNext(
                        state -> {
                            AuditChainWalk.finish(
                                    state, chain.committedSequence(), chain.committedHead());
                            chain.checkpoints()
                                    .forEach(
                                            checkpoint -> {
                                                AuditHash walkedHead =
                                                        checkpointHeads.get(
                                                                checkpoint.sequenceEnd());
                                                if (walkedHead == null
                                                        || !checkpoint
                                                                .headHash()
                                                                .equals(walkedHead)) {
                                                    throw new AuditVerificationMismatch(
                                                            "checkpoint does not match walked prefix");
                                                }
                                            });
                        })
                .thenMany(Flux.fromIterable(chain.checkpoints()))
                .concatMap(
                        checkpoint ->
                                requireValidSignature(
                                        checkpoint.signingMessage(), checkpoint.signature()))
                .then();
    }

    private Mono<Void> verifySeal(SignedEpochSeal seal) {
        DerivedEpochRoot reproduced = roots.derive(seal.evidence().material());
        if (!reproduced.equals(seal.evidence().derivedRoot())) {
            return Mono.error(
                    new AuditVerificationMismatch("sealed epoch root does not reproduce"));
        }
        return requireValidSignature(seal.evidence().signingMessage(codec), seal.signature());
    }

    private Mono<Void> requireValidSignature(
            org.meldtech.platform.audit.domain.AuditSigningMessage message,
            org.meldtech.platform.audit.domain.AuditSignature signature) {
        return signatures
                .verify(message, signature)
                .flatMap(
                        valid ->
                                valid
                                        ? Mono.empty()
                                        : Mono.error(
                                                new AuditVerificationMismatch(
                                                        "audit evidence signature is invalid")));
    }

    public record VerificationSummary(long openChains, long sealedEpochs) {}
}
