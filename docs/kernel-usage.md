# Shared Kernel Usage

Status: normative guidance for module and slice authors using
`org.meldtech.platform.shared.kernel`.

## Dependency Direction

Domain and application code may depend on kernel values and ports. The kernel
does not depend on a feature module or framework. Put Spring, WebFlux, R2DBC,
Redis, serialization, and broker adapters outside the kernel and make those
adapters implement or invoke the inward-facing port.

Import only the smallest contract a slice needs. Do not turn the kernel into a
general utility package or move feature-owned domain concepts into it.

## Required Parameters

Use the specific typed identifier for every boundary and command. A
`CandidateId` is not interchangeable with a `TenantId`, even though both wrap
UUID values. Parse untrusted text at the HTTP or message boundary and pass the
validated type inward.

Every write handler, command port, and mutating collaboration requires an
`ActorContext`. Tenant-scoped work also requires a `TenantId`, either directly
or through a contract whose signature makes the tenant explicit. The actor
contains its correlation identifier and, where applicable, tenant identity;
the tenant parameter still remains explicit on tenant-scoped query and write
contracts so isolation cannot depend on ambient state.

System work uses one of the closed `SystemActor` values. Never represent a
missing actor with `null`, an empty value, or the free-text value `"system"`.
Use `tenantSystem(...)` for tenant work and `platformSystem(...)` only for a
genuinely platform-scoped operation.

## No No-Argument Write Path

A write must not offer a no-argument overload or convenience factory that
obtains the actor, tenant, correlation identifier, or current time implicitly.
Required inputs make attribution and isolation reviewable at the call site and
make unit tests deterministic.

HTTP and message adapters may resolve request context once, but application and
domain methods receive the resulting values explicitly. Do not read Reactor
`Context`, MDC, security context, headers, or thread-local state from domain
code. Scheduler-hop propagation preserves adapter context; it is not an
alternative application API.

A typical application boundary has this shape:

```java
Publisher<Void> submit(
        TenantId tenantId,
        ActorContext actor,
        AttemptId attemptId,
        Instant authoritativeNow);
```

Prefer constructor injection for stable dependencies such as `Clock` and
ports. The application adapter obtains `authoritativeNow` from the injected
clock. Keep request-specific identity and actor values as method parameters.

## Time

Inject `org.meldtech.platform.shared.kernel.time.Clock` and call `now()` once
when an operation needs the authoritative instant. Pass that instant into
domain calculations that must share the same decision time.

Production wiring uses `platform.infra.time.SystemClock`. Tests use the fixed
clock in the test source set or a local `Clock` lambda. Outside the production
clock adapter, do not call `Instant.now()`, `System.currentTimeMillis()`, a
system JDK clock, or another ambient-time API. CI stage 4 enforces this rule.

## Ports

- Compose the publisher returned by `OutboxWriter.append(...)` into the
  caller-owned transaction. The port does not open or commit a transaction.
- Branch on every `IdempotencyStore.reserveOrReplay(...)` outcome before a side
  effect. `UNAVAILABLE` means refuse the eligible transition safely; it never
  authorizes unguarded execution.
- Obtain problem responses through `ProblemDetailMapper`; do not construct an
  alternative response shape or expose exception text.

The component ownership and adapter assignment are maintained in
[`shared-kernel.md`](shared-kernel.md).
