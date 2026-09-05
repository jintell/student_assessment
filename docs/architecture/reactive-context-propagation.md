# Reactive Request Context Propagation

Status: normative design for `FEAT-PLAT-001` (`P2.7`). Sources: architecture §§8.3-8.4 and 16.1-16.3.

## Source of Truth

Reactor `Context` is the only request-context authority. Authorization, tenant selection, audit attribution, and transaction role selection read it with `deferContextual`; no business decision reads an MDC or application `ThreadLocal`.

Phase 4 introduces a minimal immutable placeholder:

```java
record RequestCarrier(
        String correlationId,
        String tenantId,
        String actorType,
        String actorId,
        String sourceIp) {
}
```

The Reactor key is the `RequestCarrier.class` object rather than a collision-prone string. `FEAT-PLAT-003` replaces `tenantId` with `TenantId` and splits actor data into the definitive `ActorContext`; the WebFilter, handler parameter, transaction initializer, logging bridge, and outbox reconstruction point all adopt those types together.

## HTTP Entry

A highest-precedence `WebFilter`:

1. accepts `X-Correlation-Id` only when it matches the documented length/character allowlist, otherwise generates a new opaque identifier;
2. derives the placeholder request carrier from the authenticated principal and trusted proxy-resolved source address, never from arbitrary body fields;
3. writes the carrier with `chain.filter(exchange).contextWrite(context -> context.put(RequestCarrier.class, carrier))`;
4. returns the same correlation identifier on every response, including mapped errors;
5. clears no global state because none is used as the authority.

An absent carrier at a protected handler, policy, query, audit, or transaction boundary is a denial/error, never an anonymous or platform-wide fallback.

## Logging Bridge

Micrometer Context Propagation registers a narrow `ThreadLocalAccessor` for allowlisted logging fields (`correlationId`, `traceId`, `spanId`, `tenantId`, `actorType`, `actorId`, `module`, `slice`). `ContextSnapshot` restores those fields only around a logging callback and restores the previous MDC state when the scope closes. Secret-pattern fields and personal names/emails are never registered.

Automatic propagation is enabled once during application bootstrap. Reactor `Context` remains authoritative; the MDC bridge is write-only observability projection and cannot be read by policies or handlers.

## Reactive Operator Contract

- `flatMap`, `concatMap`, retries, error recovery, and nested publishers use `deferContextual` at the point context is needed rather than capturing values at assembly time.
- `publishOn` and `subscribeOn` may move execution between schedulers; automatic context propagation and the registered accessor preserve the carrier and logging projection.
- code does not use raw `ThreadLocal`, manual MDC put/remove pairs, `block()`, or scheduler-specific global mutable state;
- a new reactive chain is subscribed only by the framework or an owned worker entrypoint, never inside a handler;
- cancellation, timeout, and error paths close any `ContextSnapshot.Scope` and leave no value on the reused thread.

## Outbox and Broker Boundary

Reactor context does not cross a durable boundary implicitly. An outbox row stores the allowlisted `correlationId`, W3C trace context, tenant identifier, event identifier/type/version, and originating actor identifier/type required for audit; it stores no credential, token, source IP unless explicitly required, or arbitrary context map.

The relay reads that metadata, creates a relay span linked to the producer trace, and places it in broker headers. A consumer entrypoint validates the headers, creates a new immutable carrier with the appropriate enumerated system actor, and seeds a fresh Reactor `Context` before invoking its slice. Missing or malformed tenant/trace metadata dead-letters or rejects according to the event contract; it never falls back to platform scope.

## Verification

Tests assert that the same carrier/correlation identifier is visible after nested `flatMap`, `publishOn`, `subscribeOn`, retry, timeout, and cancellation; that MDC is populated only inside scoped callbacks and cleared afterward on the same reused thread; and that an outbox producer-to-consumer round trip reconstructs only the allowlisted fields with an enumerated system actor.
