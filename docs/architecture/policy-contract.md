# Policy Contract and Startup Assertion

Status: normative design for `FEAT-PLAT-001` (`P2.6`). Verification: `ARC-VERIFY-008` structural/startup limb.

## Contract

```java
package org.meldtech.platform.shared.api;

import reactor.core.publisher.Mono;

public interface SlicePolicy<R> {

    String routeId();

    Mono<PolicyDecision> evaluate(RequestCarrier actor, R request);
}
```

```java
package org.meldtech.platform.shared.api;

public enum PolicyDecision {
    ALLOW,
    DENY
}
```

Each slice's `Policy.java` implements `SlicePolicy<Request>` and returns the stable route identifier declared by its endpoint. The placeholder `RequestCarrier` is replaced by `FEAT-PLAT-003`'s `ActorContext` without changing the evaluation shape.

The contract is fail-closed:

- no matching policy means `DENY` and is also a startup defect;
- an empty publisher, evaluation exception, timeout, unknown decision, missing actor, or missing tenant becomes `DENY`;
- only the literal `ALLOW` result permits handler invocation;
- policy evaluation performs no mutation and starts no transaction;
- authorization facts come from platform-authoritative state, never unverified token claims or client assertions.

## Route-to-Policy Registry

Every application route exposes a stable `routeId` in its route metadata. Startup discovery collects all application `Endpoint` mappings and all `SlicePolicy` beans, then constructs a one-to-one map:

1. reject a blank or duplicate endpoint `routeId`;
2. reject a blank or duplicate policy `routeId`;
3. fail startup when an endpoint has zero policies;
4. fail startup when an endpoint has more than one policy;
5. fail startup when a policy names no endpoint;
6. publish the immutable map only after every check passes.

Framework management endpoints are not silently excluded. Health/Actuator routes enabled by the application must have an explicit platform policy or an enumerated management-route classification tested separately; adding to that classification requires review.

The validator runs after handler mappings and policy beans are available but before the application reports readiness. Its failure lists route identifiers and counts for operators, while no HTTP listener is considered ready.

## Request Behavior

The endpoint resolves its policy from the immutable registry, evaluates it before calling the handler, and uses `switchIfEmpty(DENY)` plus `onErrorReturn(DENY)`. A denial is sent only through the central problem mapper with a stable allowlisted code such as `ACCESS_DENIED`; it includes the correlation identifier but never the policy name, failed predicate, required authority, token claim, exception message, SQL, or existence of another tenant's resource.

Until `FEAT-IAM-003` supplies authoritative evaluation, policies that require an authority always return `DENY`. There is no configuration flag, fallback allow, development bypass, or permit-all implementation.

## Startup Verification

`ARC-VERIFY-008` is proven structurally with startup tests for: complete one-to-one coverage (starts), missing policy (fails), duplicate policy (fails), orphan policy (fails), and a newly registered route with no policy (fails). Runtime CI stage 10 later sweeps endpoints to prove denial behavior; this task designs only the startup limb.
