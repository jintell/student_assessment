# Phase 9 Monitoring and Operations Evidence

## P9.1 Migration duration

Verdict: **PASS**

Every valid migration Job supplies its release classification through
`CBT_MIGRATION_CLASSIFICATION`. The migration-only entrypoint validates that it
is `EXPAND`, `MIGRATE`, or `CONTRACT`, then records
`migration_duration_seconds` around every attempted module migration. Recording
runs in a `finally` block, so the module that fails is timed as well as modules
that succeed.

The timer has only the bounded `module` and `classification` tags. The Job uses
a short-lived OTLP registry; registry close performs the final publish, with
connect and read timeouts bounded by
`CBT_MIGRATION_TELEMETRY_EXPORT_TIMEOUT` (default five seconds).

Evidence:

- `MigrationApplication.runMigrations`
- `MigrationMetrics.recordDuration`
- `MigrationOtlpRegistry`
- `MigrationTelemetryTest.recordsEveryModuleDurationWithTheReleaseClassification`

## P9.2 Migration lock-hold duration

Verdict: **PASS**

`Stage12MigrationVerifier` now sends each `LockHoldMeasurement` produced by
`LockMeasurementHarness` to both the retained lock-duration report and
`migration_lock_held_seconds`. The report and metric therefore use the same
sampled maximum continuous hold, relation, and PostgreSQL lock mode; there is
no second timing calculation.

The distribution summary records seconds with bounded `module`,
schema-qualified `relation`, and enumerated `lock_mode` tags. It publishes
explicit service-level buckets at 0.1, 0.25, and 2.0 seconds. Stage 12 exports
through OTLP when an endpoint is configured and always retains the JSON/Markdown
report as the per-release source evidence.

Evidence:

- `LockMeasurementHarness` and `LockHoldMeasurement`
- `Stage12MigrationVerifier.measureRelease`
- `MigrationVerificationMetrics.recordLockHeld`
- `MigrationVerificationMetricsTest.publishesTheHarnessMeasurementWithoutChangingItsDefinition`

## P9.3 Migration outcomes and static refusals

Verdict: **PASS**

The migration-only entrypoint increments `migration_outcome_total` exactly once
after a successful full Job or before propagating execution failure. An
interrupted failure is classified `CANCELLED`; other runtime migration failures
are `EXECUTION_FAILED`. Classification and outcome are enum-bounded tags.

Stage 12 increments `migration_forbidden_operation_total` for each analyser
violation using its named rejection code, and records the terminal
`STATIC_REFUSAL` outcome before failing the gate. Statement text, paths, error
messages, release IDs, and other high-cardinality values are not metric tags.

Evidence:

- `MigrationApplication.runMigrations`
- `MigrationMetrics.recordOutcome` and `recordForbiddenOperation`
- `Stage12MigrationVerifier.measureRelease`
- `MigrationTelemetryTest.recordsExecutionFailureBeforePropagatingIt`
- `MigrationVerificationMetricsTest.publishesOutcomeAndForbiddenOperationCounters`

## P9.4 Deploy-freeze refusals

Verdict: **PASS**

`DeployFreezePrecondition` records `deploy_freeze_refusal_total` for every
refusal through the injected `DeployFreezeRefusalRecorder`. The bounded reason
vocabulary distinguishes `SESSION_OPEN` from `SOURCE_UNKNOWN`; the latter
covers missing configuration, empty results, source timeout, and source
unavailability while retaining the detailed window reason outside metric tags.

A sustained `SOURCE_UNKNOWN` rate means the safety control is working
fail-closed but is blind: deployments are being refused without an
authoritative view of session state. Operators must restore the session-window
source rather than suppressing or reclassifying the refusal.

Evidence:

- `DeployFreezePrecondition` and `DeployFreezeRefusalReason`
- `MigrationMetrics.record`
- `DeployFreezePreconditionTest.unknownRefuses`
- `DeployFreezePreconditionTest.errorsAndEmptyResultsFailClosedAsUnknown`
- `MigrationMetricsTest.publishesTheFiveContractedMetersWithBoundedTags`

## P9.5 Alert and dashboard ownership gap

`TASK-PLAT5-OBS-001` is raised to `FEAT-OBS-001` and `FEAT-OPS-004` in
`docs/defects/TASK-PLAT5-OBS-001.md`. It proposes the P2 lock-threshold alert,
the P1 production migration-failure alert, their first actions and closure
evidence, and five platform-health panels. The record remains open until those
owners register, route, and exercise the definitions.

## P9.6 Failed-migration operations runbook

`docs/runbook-migration-failure.md` covers report validation, lock versus
forbidden-operation triage, idempotent `INVALID` index reconciliation, guarded
Flyway schema-history repair, compatibility failure, and the forward-fix rule.
It explicitly refuses manual schema-history edits and schema rollback.

## P9.7 Blocked-deploy operations runbook

`docs/runbook-blocked-deploy.md` covers open-session confirmation, authoritative
window discovery, each unknown-source reason, and the incident-bound emergency
override. It records the exact two distinct approval roles, audit-before-permit
rule, scope/expiry checks, and the gates an override can never bypass.

## P9.8 Observability conformance

The conformance assessment is retained in
`docs/evidence/FEAT-PLAT-005/P9.8-observability-conformance.md`. Duration, lock
duration, and outcome are attributable per release; lock-threshold failures
produce metrics and blocking retained evidence rather than only a log. The
record references open `TASK-PLAT5-OBS-001` and names `FEAT-OBS-001` and
`FEAT-OPS-004` as the registration, routing, dashboard, and exercise owners.
