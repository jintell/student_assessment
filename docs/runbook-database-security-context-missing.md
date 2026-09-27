# Database Security Context Missing Runbook

Use this runbook when `DatabaseSecurityContextMissing` fires. Any increment of
`db_context_missing_total` is a P1 incident and a latent tenant-isolation
defect. A persistence path attempted database work without the mandatory role
and tenant or platform scope.

## What the Alert Means

`SecurityContextInitializer` permits caller statements only after a transaction
has installed, in order, `SET LOCAL ROLE`, the tenant or enumerated platform
scope, and the role-specific search path. The counter increments when the
connection guard refuses a statement or batch outside that `READY` state and
raises `R9_CONTEXT_NOT_FIRST`.

The backstop held: the wrapper rejected the operation before delegating the
caller statement. The pool login role also has no direct object grants, and
tenant tables retain forced RLS. Treat this as evidence that an application
path violated the transaction contract, not as evidence that unscoped access
is harmless.

## Immediate Response

1. Acknowledge the P1 and record the alert start, environment, workload,
   deployment version, and incident reference.
2. Stop or roll back the newly defective route, consumer, or release. If its
   scope is not yet known, pause the affected workload rather than allowing
   repeated refusals.
3. Preserve the alert evaluation, the five-minute metric window, relevant
   traces, and sanitized logs. Do not include tenant identifiers, SQL
   parameters, credentials, or personal data in the incident record.
4. Confirm the counter has stopped increasing after containment. This confirms
   containment only; it does not close the incident.

## Find the Calling Path

1. Search application logs and error traces in the alert window for
   `R9_CONTEXT_NOT_FIRST`. Use the existing trace or correlation identifier to
   locate the slice boundary and workload; never add tenant or SQL labels to
   the metric.
2. Compare deployments and traffic changes immediately before the first
   increment. Identify whether the caller was an HTTP slice, broker consumer,
   scheduler, or startup path.
3. Inspect the path from the slice handler to its transaction boundary. It must
   use `inTenantTransaction` or the authorised `inPlatformTransaction` path and
   must not create a statement or batch before transaction initialisation.
4. Verify the selected workload pool is wrapped by
   `SecurityContextInitializer`. Check for direct use of a delegate connection
   factory, early subscription before Reactor context installation, or work
   that escaped the managed transaction publisher.
5. Correlate companion signals:
   - `db_context_install_failure_total` increasing by role means context
     installation began but PostgreSQL rejected the role or tenant statement.
   - `db_connection_reset_failure_total` increasing means connection cleanup
     failed and the physical connection must be evicted and investigated.
   - normal `db_role_assumption_total` for the expected role confirms only the
     baseline; it does not cancel the missing-context event.
6. Reproduce the path with a focused test that first proves the refusal, then
   proves the corrected transaction installs context before its first caller
   statement.

## Forbidden Mitigations

Do not silence, downgrade, or add a delay to the alert. Do not grant object
privileges to a pool login role, inject a default or wildcard tenant, set
`row_security = off`, catch and ignore `R9_CONTEXT_NOT_FIRST`, expose the raw
connection factory, or bypass `SecurityContextInitializer` for health or
background work.

The signal has a zero-event budget because it reports a broken isolation
invariant. Today the guard denied the operation; a later grant, policy, or
calling-path defect could turn the same hidden violation into cross-tenant
access. Repeated increments are separate defects, not alert noise.

## Restore and Close

Restore traffic only after:

- the defective path is corrected and reviewed;
- focused tests and `ARC-VERIFY-024` pass against the release candidate;
- pool-role grants and forced-RLS catalogue checks remain green;
- the P1 alert drill still fires on a synthetic increment; and
- `db_context_missing_total` shows no new production increments during the
  agreed observation window.

Retain the root cause, affected path, change reference, test results,
`ARC-VERIFY-024` report, alert-delivery evidence, and observation window under
the incident. Close only after the operational owner confirms the alert remains
enabled and routed.
