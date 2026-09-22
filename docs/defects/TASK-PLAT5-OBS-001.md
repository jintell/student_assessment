# TASK-PLAT5-OBS-001 Migration Alerts and Dashboard Gap

Status: **OPEN - HANDED OFF**

Owners: `FEAT-OBS-001` (metric registration), `FEAT-OPS-004` (rules, routing,
dashboard, and exercise evidence)

## Gap

`FEAT-PLAT-005` emits and retains migration telemetry, but the approved
observability baseline contains no registered migration alerts or platform
health panels. Until the owners install and exercise the definitions below, a
threshold breach is measurable but not yet guaranteed to page an operator.

## Proposed alerts

### MigrationLockThresholdWarning - P2

Fire when an exam-critical relation exceeds the 0.1-second warning threshold
or any other relation exceeds its 2-second failure threshold during a release:

```promql
max_over_time(migration_lock_held_seconds_max{relation=~"delivery\\.(answer|answer_operation|attempt)|audit\\.audit_event"}[5m]) >= 0.1
or
max_over_time(migration_lock_held_seconds_max{relation!~"delivery\\.(answer|answer_operation|attempt)|audit\\.audit_event"}[5m]) >= 2
```

First action: **check for an open session and prepare a forward fix**. Keep the
release blocked, inspect the retained relation/mode report and live
`pg_locks`, and do not attempt schema rollback.

### MigrationFailedInProduction - P1

Fire on any terminal production failure:

```promql
sum(increase(migration_outcome_total{environment="production",outcome=~"(EXECUTION|LOCK_THRESHOLD|COMPATIBILITY)_FAILED"}[5m])) > 0
```

The plan's shorthand `outcome="FAILED"` is not an emitted label value. The
bounded contract preserves the actionable outcomes `EXECUTION_FAILED`,
`LOCK_THRESHOLD_FAILED`, and `COMPATIBILITY_FAILED`; the owner must use the
explicit selector above or introduce a reviewed recording rule named
`migration_outcome_failed_total`. It must not add an unbounded exception label.

First action: halt rollout, retain the Job and stage-12 evidence, classify the
failure, and follow the failed-migration runbook.

## Proposed platform-health panel

| Panel | Query / view | Purpose |
|---|---|---|
| Migration duration | p50/p95/max of `migration_duration_seconds` by module and classification | Identify release and module regressions |
| Lock holds | max and threshold buckets of `migration_lock_held_seconds` by relation and lock mode | Show the exact relation and compatibility impact |
| Outcomes | rate/increase of `migration_outcome_total` by classification and outcome | Show success and terminal failure trends |
| Static refusals | increase of `migration_forbidden_operation_total` by rejection code | Expose recurring authoring defects |
| Freeze refusals | increase of `deploy_freeze_refusal_total` by reason | Separate legitimate open-session blocks from blind `SOURCE_UNKNOWN` failures |

All panels must link to the retained release manifest, lock-duration report,
and the operations runbooks without using release IDs or incident identifiers
as metric labels.

## Closure criteria

- Both alert rules are registered, routed, and exercised.
- The migration panel is present on the platform-health dashboard.
- A synthetic warning and failure produce retained notification evidence.
- Runbook links resolve and the first-action text matches this record.
