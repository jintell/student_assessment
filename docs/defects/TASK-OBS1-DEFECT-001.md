# TASK-OBS1-DEFECT-001 Unowned Telemetry Backend

Status: **DEFERRED TO PLATFORM OPS PHASE 0 PROVISIONING - PRODUCTION BLOCKER**

Owners: Platform Ops (collector, metric store, and log store), Engineering
Lead (`FEAT-OBS-001` application/export contract)

Architecture documentation owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Gap

The plan requires the observability stack to be provisioned in parallel during
Phase 0, but assigns no feature to the collector, metric store, or log store.
`FEAT-OPS-004` owns dashboards, alert rules, routing, and exercise evidence;
it does not own backend provisioning. The architecture requires OTLP and
tail-based sampling without naming a product, endpoint, workload identity,
retention policy, sizing model, or availability target.

## Ownership and Deferral

Plan section 22 assigns Phase 0 observability-stack provisioning to Platform
Ops. Platform Ops therefore owns the collector and both stores, including
capacity, retention, availability, TLS, workload identity, and the tail
sampling processor. The Engineering Lead owns the application's vendor-neutral
OTLP contract and must not introduce a product SDK or an in-process storage
assumption.

The product selection and production endpoint are explicitly deferred to the
Platform Ops Phase 0 provisioning track. This deferral does not authorize a
production deployment without the stack.

## Interim Endpoint

Development and CI use an ephemeral OTLP-compatible sink supplied by
`FEAT-OBS-001` task `P3.7`. The application reads the OTLP/gRPC endpoint from
external configuration and carries no committed environment endpoint or
credential. The interim sink is test infrastructure only; it is not a
production backend and provides no retention or availability claim.

## Blocking Handoff

Before observability task `P3.5` can complete, Platform Ops and the Engineering
Lead must record a collector contract that names:

- the environment-specific OTLP/gRPC endpoint and certificate authority;
- the workload identity and least-privilege authentication mechanism;
- the tail-sampling processor that retains all errors and the required route
  classes;
- the metric and log stores, retention periods, capacity owner, and
  availability expectation; and
- the failure boundary proving an unavailable or slow backend cannot block a
  request or Reactor scheduler.

Until that contract exists, `P3.5`, production deployment, and the Phase 0
exit criterion for a provisioned observability stack remain blocked.

## Closure Criteria

- Platform Ops provides the production-shaped collector contract.
- The endpoint, TLS, and workload identity are verified from each runtime
  role without committing secrets.
- Tail sampling retains all errors and the required exam-entry and grading
  paths.
- Metric and log storage ownership, retention, sizing, and availability are
  recorded.
- Export failure is demonstrated to degrade telemetry only.
