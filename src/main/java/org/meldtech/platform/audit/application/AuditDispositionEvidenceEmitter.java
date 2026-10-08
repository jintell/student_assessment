package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.audit.domain.ShardSequenceRange;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import reactor.core.publisher.Mono;

public final class AuditDispositionEvidenceEmitter {

    public static final String EVENT_TYPE = "audit.AUDIT_EPOCH_DISPOSED.v1";

    private final AuditEmitter emitter;

    public AuditDispositionEvidenceEmitter(AuditEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    public Mono<Void> emit(
            DispositionRequest request,
            SignedEpochSeal seal,
            ActorContext actor,
            Instant occurredAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(seal, "seal");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
        validateIdentity(request, seal);
        return Mono.from(emitter.emit(toEvent(request, seal), actor, occurredAt));
    }

    private static AuditEvent toEvent(DispositionRequest request, SignedEpochSeal seal) {
        List<CanonicalValue> ranges = new ArrayList<>();
        for (ShardSequenceRange range : seal.evidence().derivedRoot().sequenceRanges()) {
            ranges.add(
                    new ObjectValue(
                            Map.of(
                                    "shard_id", new IntegerValue(range.shardId()),
                                    "seq_start", optional(range.start()),
                                    "seq_end", optional(range.end()))));
        }
        ObjectValue signatureReference =
                new ObjectValue(
                        Map.of(
                                "key_version", new StringValue(seal.signature().keyVersion()),
                                "request_id", new StringValue(seal.signature().providerRequestId()),
                                "algorithm", new StringValue(seal.signature().algorithm())));
        ObjectValue payload =
                new ObjectValue(
                        Map.ofEntries(
                                Map.entry(
                                        "disposed_retention_class",
                                        new StringValue(request.epoch().retentionClass().name())),
                                Map.entry(
                                        "disposed_period",
                                        new StringValue(request.epoch().period().toString())),
                                Map.entry(
                                        "root_seq",
                                        new IntegerValue(
                                                seal.evidence().material().rootSequence())),
                                Map.entry("sequence_ranges", new ArrayValue(ranges)),
                                Map.entry(
                                        "root_hash",
                                        new StringValue(
                                                seal.evidence().derivedRoot().epochRoot().hex())),
                                Map.entry("signature_reference", signatureReference),
                                Map.entry("policy_key", new StringValue(request.policyKey())),
                                Map.entry(
                                        "policy_version",
                                        new IntegerValue(request.policyVersion())),
                                Map.entry(
                                        "request_id",
                                        new StringValue(request.requestId().toString())),
                                Map.entry(
                                        "authorization_reference",
                                        new StringValue(request.authorizationReference()))));
        String epochReference =
                request.epoch().retentionClass().name() + ":" + request.epoch().period();
        return new AuditEvent(
                EVENT_TYPE,
                new EntityRef("audit.epoch", epochReference),
                Set.of(RetentionClass.RESULT_CORRECTION_EVIDENCE),
                payload);
    }

    private static CanonicalValue optional(java.util.OptionalLong value) {
        return value.isPresent() ? new IntegerValue(value.orElseThrow()) : NullValue.INSTANCE;
    }

    private static void validateIdentity(DispositionRequest request, SignedEpochSeal seal) {
        if (!request.tenantId().equals(seal.evidence().material().tenantId())
                || !request.epoch().equals(seal.evidence().material().epoch())) {
            throw new IllegalArgumentException("seal does not belong to the disposition request");
        }
    }
}
