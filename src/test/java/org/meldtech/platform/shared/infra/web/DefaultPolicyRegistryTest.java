package org.meldtech.platform.shared.infra.web;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.PolicyDecision;
import org.meldtech.platform.shared.api.RequestCarrier;
import org.meldtech.platform.shared.api.SlicePolicy;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultPolicyRegistryTest {

    private static final RequestCarrier CARRIER =
            new RequestCarrier("request-123", Optional.empty(), Optional.empty(), "127.0.0.1");

    @Test
    void deniesWhenNoPolicyExists() {
        DefaultPolicyRegistry registry = new DefaultPolicyRegistry(List.of());

        StepVerifier.create(registry.evaluate("route", CARRIER, "request"))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void deniesWhenPolicyDoesNotProduceADecision() {
        DefaultPolicyRegistry registry = new DefaultPolicyRegistry(List.of(policy(Mono.empty())));

        StepVerifier.create(registry.evaluate("route", CARRIER, "request"))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void deniesWhenPolicyEvaluationFails() {
        DefaultPolicyRegistry registry =
                new DefaultPolicyRegistry(
                        List.of(policy(Mono.error(new IllegalStateException("failure")))));

        StepVerifier.create(registry.evaluate("route", CARRIER, "request"))
                .expectNext(PolicyDecision.DENY)
                .verifyComplete();
    }

    @Test
    void preservesAnExplicitAllowDecision() {
        DefaultPolicyRegistry registry =
                new DefaultPolicyRegistry(List.of(policy(Mono.just(PolicyDecision.ALLOW))));

        StepVerifier.create(registry.evaluate("route", CARRIER, "request"))
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
            public Mono<PolicyDecision> evaluate(RequestCarrier carrier, String request) {
                return decision;
            }
        };
    }
}
