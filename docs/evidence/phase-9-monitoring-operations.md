# Phase 9 Monitoring and Operations Evidence

## P9.1 Reference Slice Trace

- Verified on: 2026-09-07
- Contract: `docs/architecture/span-contract.md`
- Span name: `platform.getConformanceReference`
- Command: `./gradlew test --tests 'org.meldtech.platform.platform.slice.getConformanceReference.SliceTest'`
- Trace assertion: `SliceTest.emitsOneSpanAtTheSliceBoundary`
- Result: one and only one finished span for one successful slice invocation
- Verified tags: `module`, `slice`, `audience`, `actorType`, `operation`, `correlationId`, and `tenantId`

The test uses Micrometer's tracing test implementation and the same `ObservationRegistry` integration used by
the application. The span contains identifiers and enumerations only; it contains no source IP, actor ID,
credential, request body, or personal-data field.

## P9.2 Correlation and Log Fields

`docs/architecture/log-field-contract.md` publishes `correlationId` as the canonical log-field name and
`X-Correlation-Id` as its HTTP carrier. Inspection of `RequestContextWebFilter` and `RequestCarrier` confirms
that the value is copied as an opaque identifier and is not composed from tenant, actor, source-address, or
request-content data. The contract prohibits encoding personal data into it and defines the complete logging
allowlist for `FEAT-OBS-001` to implement.

## P9.3 End-to-End Correlation Propagation

- Verified on: 2026-09-07
- Command: `./gradlew test --tests 'org.meldtech.platform.platform.slice.getConformanceReference.SliceTest'`
- Evidence test: `SliceTest.propagatesCorrelationIdFromFilterToLogAndSpan`
- Input header: `X-Correlation-Id: request-123`
- Response header `X-Correlation-Id`: `request-123`
- Slice log MDC `correlationId`: `request-123`
- Slice span `correlationId` tag: `request-123`

The test uses the production `RequestContextWebFilter`, production Reactor context/MDC bridge, and production
reference endpoint. Equality across all three outputs proves that no replacement or divergent identifier is
introduced between the HTTP boundary, log projection, and slice span.

## P9.4 Engineering Failure Notifications

`.github/workflows/ci.yml` now captures bounded first-failure outputs from Stage 4a and architecture
conformance. On failure, the `engineering-notification` job writes a change-visible check annotation and job
summary containing the stage, failing rule or Stage 4a step, first-failure reason, commit, ref, and run link.
`.github/ci-notifications.md` registers that checks surface as the engineering channel.

Verification on 2026-09-07:

- workflow YAML parsed successfully;
- a simulated Stage 4a mismatch extracted `Stage 4a step 9` and its first reason;
- a simulated conformance failure extracted `R3` and its first reason;
- `ci/test-stage-4a-bypass` passed, confirming no failure downgrade was introduced; and
- `ci/verify-workflow-security` passed with read-only permissions and no added secret consumer.

The annotation exposes the blocking reason without requiring an engineer to open the failed gate log.

## P9.5 Statelessness Review

`docs/architecture/statelessness-review.md` records the source and configuration audit. No request-surviving
mutable state, HTTP/WebFlux session mechanism, application cache, scheduler-owned state, manual reactive
subscription, sticky-session setting, or affinity configuration was found. Request data remains in immutable
Reactor context and method-local values. The document explicitly leaves deployment/no-affinity verification
and the replica-removal staging drill to `FEAT-PLAT-006`.
