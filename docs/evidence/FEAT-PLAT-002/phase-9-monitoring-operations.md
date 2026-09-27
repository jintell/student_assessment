# Phase 9 Monitoring and Operations Evidence

## P9.1 Context Installation Failures

Verdict: **PASS**

`SecurityContextInitializer.DatabaseContextMetrics` registers
`db_context_install_failure_total` with the requested role as its only tag.
The role comes from the closed `AssumableDatabaseRole` enumeration, so the
label is bounded and cannot contain tenant, actor, SQL, or exception data.

The counter increments when either mandatory role or tenant context statement
fails, before cleanup begins. The original failure remains visible to the
caller and the connection follows the rollback and reset path.

Evidence:

- `SecurityContextInitializer.internalStatement`
- `SecurityContextInitializerTest.recordsContextInstallationFailures`
- Focused Gradle test passed on 2026-09-27

## P9.2 Missing Context Refusals

Verdict: **PASS**

`db_context_missing_total` is an untagged counter. It increments whenever the
connection guard refuses a statement or batch because the connection is not in
the `READY` state with an installed role and tenant or platform scope. The
refused operation raises `R9_CONTEXT_NOT_FIRST`; metric recording does not
replace or suppress that failure.

Any increment is a latent tenant-isolation defect. The database backstop has
prevented unscoped access, but the calling path is invalid and requires P1
incident handling. It is not a noise signal and must not be silenced.

Evidence:

- `SecurityContextInitializer.SecurityContextConnection.requireReady`
- `SecurityContextInitializerTest.recordsContextRefusalAndSuccessfulRoleAssumptionWithoutSensitiveTags`
- Focused Gradle test passed on 2026-09-27

## P9.3 Role Assumptions and Reset Failures

Verdict: **PASS**

`db_role_assumption_total` carries only the bounded `role` tag and increments
after `SET LOCAL ROLE` succeeds. Failed assumptions do not create a successful
usage sample. `db_connection_reset_failure_total` is untagged and increments
once when guarded connection cleanup fails. Cleanup continues through
`RESET ROLE`, `RESET ALL`, and close so the failure cannot skip the remaining
defensive steps.

Evidence:

- `SecurityContextInitializer.internalStatement`
- `SecurityContextInitializer.SecurityContextConnection.cleanup`
- `SecurityContextInitializerTest.recordsContextRefusalAndSuccessfulRoleAssumptionWithoutSensitiveTags`
- `SecurityContextInitializerTest.refusesSuccessfulReleaseWhenConnectionResetFails`
- Focused Gradle tests passed on 2026-09-27

## P9.4 Database Security Context Alerts

Verdict: **PASS**

`config/alerting/database-security-context.rules.yml` defines two short-window
counter-increase alerts:

| Alert | Expression | Severity |
|---|---|---|
| `DatabaseSecurityContextMissing` | `increase(db_context_missing_total[5m]) > 0` | P1 |
| `DatabaseContextInstallFailure` | `increase(db_context_install_failure_total[5m]) > 0` | P2 |

The rules use counter increases instead of absolute counter values so process
restarts cannot erase an event and an old event does not continuously re-page.
The installation alert preserves the bounded `role` label for triage.

`config/alerting/database-security-context.test.yml` first proves that neither
alert fires before an increment, then injects one increment into each counter.
Prometheus `promtool` 3.10.0 evaluated the rules successfully on 2026-09-27:

```text
SUCCESS
```

The drill asserted P1 labels for the missing-context alert and P2 plus
`role="app_delivery"` for the installation-failure alert.

## P9.5 Dashboard Ownership Gap

`TASK-PLAT2-OBS-001` is raised to `FEAT-OBS-001`, with `FEAT-OPS-004` named as
the dashboard owner. The record proposes a database security-context panel
group on dashboard 6, Platform Health, covering all four counters and linking
the P1 signal to its runbook and retained `ARC-VERIFY-024` evidence.

The gap remains open until the downstream owners register, route, render, and
exercise the dashboard and alerts. This feature does not misrepresent a
proposed panel as an installed launch dashboard.

## P9.6 Missing-Context P1 Runbook

`docs/runbook-database-security-context-missing.md` defines P1 containment,
evidence preservation, calling-path diagnosis, companion-signal correlation,
forbidden mitigations, restoration gates, and closure evidence. It explains
that the connection guard refused the caller statement before delegation while
the no-direct-grant and forced-RLS layers remained in place.

The runbook makes the alert non-silenceable: an increment is proof of a broken
isolation invariant even when the backstop prevented access.

## P9.7 Observability Conformance

The conformance assessment is retained in
`docs/evidence/FEAT-PLAT-002/P9.7-observability-conformance.md`. It traces each
failure signal from its exact state transition through metric registration,
focused verification, exercised alert rule, and operator action. Isolation
context violations and context-installation failures are observable and
alertable rather than silent.

The result is pass with the downstream dashboard handoff from
`TASK-PLAT2-OBS-001` still open; no unimplemented dashboard is represented as
delivered by this feature.
