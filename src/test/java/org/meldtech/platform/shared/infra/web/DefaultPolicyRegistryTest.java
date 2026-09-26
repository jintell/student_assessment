package org.meldtech.platform.shared.infra.web;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.SlicePolicy;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultPolicyRegistryTest {

    private static final ActorContext ACTOR =
            ActorContext.tenantWorkforce(
                    new ActorId("operator-123"),
                    TenantId.parse("ad25adad-f989-4a62-9754-3a600e5bf347"),
                    CorrelationId.parse("01J9Z9Q9J6Y7TQ4PXKJ4D0M3NV"),
                    SourceIp.parse("127.0.0.1"));

    @Test
    void deniesWhenNoPolicyExists() {
        DefaultPolicyRegistry registry = new DefaultPolicyRegistry(List.of());

        StepVerifier.create(registry.evaluate("route", ACTOR, "request"))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void deniesWhenPolicyDoesNotProduceADecision() {
        DefaultPolicyRegistry registry = new DefaultPolicyRegistry(List.of(policy(Mono.empty())));

        StepVerifier.create(registry.evaluate("route", ACTOR, "request"))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void deniesWhenPolicyEvaluationFails() {
        DefaultPolicyRegistry registry =
                new DefaultPolicyRegistry(
                        List.of(policy(Mono.error(new IllegalStateException("failure")))));

        StepVerifier.create(registry.evaluate("route", ACTOR, "request"))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void preservesAnExplicitAllowDecision() {
        DefaultPolicyRegistry registry =
                new DefaultPolicyRegistry(List.of(policy(Mono.just(PolicyDecision.ALLOW))));

        StepVerifier.create(registry.evaluate("route", ACTOR, "request"))
                .expectNext(PolicyDecision.ALLOW)
                .verifyComplete();
    }

    private static SlicePolicy<String> policy(Mono<PolicyDecision> decision) {
        return new SlicePolicy<>() {
            @Override
            public String routeId() {
                return "route";
            }

            @Override
            public Mono<PolicyDecision> evaluate(ActorContext actor, String request) {
                return decision;
            }
        };
    }
}
