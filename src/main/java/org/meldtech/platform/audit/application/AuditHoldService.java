package org.meldtech.platform.audit.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.IntegerValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import reactor.core.publisher.Mono;

public final class AuditHoldService {

    public static final String SUPPRESSED_EVENT = "audit.AUDIT_EPOCH_DISPOSITION_SUPPRESSED.v1";
    public static final String RELEASED_EVENT = "audit.AUDIT_EPOCH_HOLD_RELEASED.v1";

    private final AuditHoldRepository repository;
    private final AuditEmitter emitter;

    public AuditHoldService(AuditHoldRepository repository, AuditEmitter emitter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    public Mono<HoldResult> suspendIfHeld(
            DispositionRequest request,
            List<ActiveLegalHold> activeHolds,
            ActorContext actor,
            Instant detectedAt) {
        Objects.requireNonNull(request, "request");
        List<ActiveLegalHold> holds =
                List.copyOf(activeHolds).stream()
                        .distinct()
                        .sorted(
                                Comparator.comparing(ActiveLegalHold::holdReference)
                                        .thenComparing(ActiveLegalHold::legalBasisReference))
                        .toList();
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (holds.isEmpty()) {
            return Mono.just(HoldResult.ELIGIBLE);
        }
        return repository
                .suspend(request, holds, detectedAt)
                .flatMap(
                        firstSuspension ->
                                firstSuspension
                                        ? Mono.from(
                                                        emitter.emit(
                                                                suspensionEvent(
                                                                        request, holds, detectedAt),
                                                                actor,
                                                                detectedAt))
                                                .thenReturn(HoldResult.HOLD_SUSPENDED)
                                        : Mono.just(HoldResult.HOLD_SUSPENDED));
    }

    public Mono<ResumedDisposition> release(
            DispositionRequest request,
            List<ActiveLegalHold> remainingHolds,
            ActorContext actor,
            Instant releasedAt) {
        Objects.requireNonNull(request, "request");
        List<ActiveLegalHold> holds = List.copyOf(remainingHolds);
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(releasedAt, "releasedAt");
        if (!holds.isEmpty()) {
            return Mono.error(new IllegalStateException("all partition holds must be inactive"));
        }
        return repository
                .release(request, releasedAt)
                .flatMap(
                        resumed -> {
                            if (!resumed.originalRetentionStart()
                                            .equals(request.originalRetentionStart())
                                    || !resumed.originalDueAt().equals(request.dueAt())) {
                                return Mono.error(
                                        new IllegalStateException(
                                                "hold release attempted to reset retention time"));
                            }
                            return Mono.from(
                                            emitter.emit(
                                                    releaseEvent(request, releasedAt),
                                                    actor,
                                                    releasedAt))
                                    .thenReturn(resumed);
                        });
    }

    private static AuditEvent suspensionEvent(
            DispositionRequest request, List<ActiveLegalHold> holds, Instant detectedAt) {
        List<CanonicalValue> references = new ArrayList<>(holds.size());
        for (ActiveLegalHold hold : holds) {
            references.add(
                    new ObjectValue(
                            Map.of(
                                    "hold_reference", new StringValue(hold.holdReference()),
                                    "legal_basis_reference",
                                            new StringValue(hold.legalBasisReference()))));
        }
        return event(
                SUPPRESSED_EVENT,
                request,
                Map.ofEntries(
                        Map.entry("hold_references", new ArrayValue(references)),
                        Map.entry("detected_at", new InstantValue(detectedAt))));
    }

    private static AuditEvent releaseEvent(DispositionRequest request, Instant releasedAt) {
        return event(RELEASED_EVENT, request, Map.of("released_at", new InstantValue(releasedAt)));
    }

    private static AuditEvent event(
            String eventType,
            DispositionRequest request,
            Map<String, CanonicalValue> additionalFields) {
        Map<String, CanonicalValue> fields = new java.util.HashMap<>(additionalFields);
        fields.put(
                "partition",
                new StringValue(
                        request.epoch().retentionClass().name() + ":" + request.epoch().period()));
        fields.put("original_retention_start", new InstantValue(request.originalRetentionStart()));
        fields.put("original_due_at", new InstantValue(request.dueAt()));
        fields.put("policy_key", new StringValue(request.policyKey()));
        fields.put("policy_version", new IntegerValue(request.policyVersion()));
        fields.put("request_id", new StringValue(request.requestId().toString()));
        return new AuditEvent(
                eventType,
                new EntityRef("audit.epoch", request.epoch().toString()),
                Set.of(RetentionClass.RESULT_CORRECTION_EVIDENCE),
                new ObjectValue(fields));
    }

    public enum HoldResult {
        ELIGIBLE,
        HOLD_SUSPENDED
    }
}
