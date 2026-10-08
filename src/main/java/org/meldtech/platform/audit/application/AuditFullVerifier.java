package org.meldtech.platform.audit.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.meldtech.platform.audit.domain.AuditChainWalk;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootChainValidator;
import org.meldtech.platform.audit.domain.AuditVerificationMismatch;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class AuditFullVerifier {

    private final AuditFullVerificationEvidence evidence;
    private final AuditSignatureVerifier signatures;
    private final AuditRootChainValidator rootChains;
    private final CanonicalJsonCodec codec;

    public AuditFullVerifier(
            AuditFullVerificationEvidence evidence,
            AuditSignatureVerifier signatures,
            AuditRootChainValidator rootChains,
            CanonicalJsonCodec codec) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.rootChains = Objects.requireNonNull(rootChains, "rootChains");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public Mono<FullVerificationSummary> verify(Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        Mono<Long> chainCount =
                evidence.retainedChains()
                        .concatMap(chain -> verifyChain(chain).thenReturn(chain))
                        .count();
        Mono<Long> tenantCount =
                evidence.tenantRootChains()
                        .concatMap(roots -> verifyRootChain(roots).thenReturn(roots))
                        .count();
        return Mono.zip(chainCount, tenantCount)
                .map(
                        counts ->
                                new FullVerificationSummary(
                                        trigger, counts.getT1(), counts.getT2()));
    }

    private Mono<Void> verifyChain(OpenAuditChain chain) {
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
                                                AuditHash walked =
                                                        checkpointHeads.get(
                                                                checkpoint.sequenceEnd());
                                                if (walked == null
                                                        || !checkpoint.headHash().equals(walked)) {
                                                    throw new AuditVerificationMismatch(
                                                            "checkpoint does not match retained chain");
                                                }
                                            });
                        })
                .thenMany(Flux.fromIterable(chain.checkpoints()))
                .concatMap(
                        checkpoint ->
                                requireSignature(
                                        checkpoint.signingMessage(), checkpoint.signature()))
                .then();
    }

    private Mono<Void> verifyRootChain(TenantRootEvidence evidence) {
        rootChains.validate(evidence.tenantId(), evidence.seals(), evidence.currentHead());
        return Flux.fromIterable(evidence.seals())
                .concatMap(
                        seal ->
                                requireSignature(
                                        seal.evidence().signingMessage(codec), seal.signature()))
                .then();
    }

    private Mono<Void> requireSignature(
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

    public enum Trigger {
        QUARTERLY,
        POST_RESTORE
    }

    public record FullVerificationSummary(Trigger trigger, long retainedChains, long tenants) {}
}
