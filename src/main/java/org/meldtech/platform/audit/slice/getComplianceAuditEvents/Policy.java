package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.util.Objects;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorType;
import reactor.core.publisher.Mono;

public final class Policy implements SlicePolicy<Request> {

    public static final String REQUIRED_CAPABILITY = "AUDIT_COMPLIANCE_READ";
    private final AuditComplianceCapabilityView capabilities;

    public Policy(AuditComplianceCapabilityView capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    @Override
    public String routeId() {
        return Endpoint.ROUTE_ID;
    }

    @Override
    public Mono<PolicyDecision> evaluate(ActorContext actor, Request request) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        if (actor.actorType() != ActorType.WORKFORCE_USER || actor.tenantId().isEmpty()) {
            return Mono.just(PolicyDecision.DENY);
        }
        return capabilities
                .hasCapability(actor.actorId(), actor.tenantId().orElseThrow(), REQUIRED_CAPABILITY)
                .map(allowed -> allowed ? PolicyDecision.ALLOW : PolicyDecision.DENY)
                .defaultIfEmpty(PolicyDecision.DENY)
                .onErrorReturn(PolicyDecision.DENY);
    }
}
