# Sweep Registry Changes

`platform.sweep_registry` controls whether a declared sweep runs and its default
interval. Every enable, disable, or interval change is a privileged platform
mutation and must be attributable.

## Current Control State

Runtime task P6.9 is blocked: the audit store has no supported tenantless
platform-workforce emission contract, so no conforming operator mutation
command exists. `app_worker` has `SELECT` only. Until P6.9 is delivered:

- do not update the table with ad hoc SQL;
- do not grant `UPDATE` to a runtime or operations login;
- change static defaults only through a reviewed additive migration; and
- treat an unexplained row change as a security incident.

The required audit event is `platform.SWEEP_REGISTRY_CHANGED.v1`. A log line or
ticket does not replace it.

## Future Audited Procedure

Use this procedure only after the platform-scoped audit port and operator
command are implemented and verified.

1. Confirm the sweep name, owning feature/module, declared system actor,
   current value, requested value, reason, approver, and incident/change ID.
2. Validate the new interval against the runtime bounds and the owning
   feature's batch/tick limits. Never clamp an invalid value.
3. Execute exactly one registry mutation and emit
   `platform.SWEEP_REGISTRY_CHANGED.v1` on the same caller-owned R2DBC
   connection and transaction.
4. Require the event payload to contain only sweep name, changed setting,
   previous value, new value, actor attribution, and change reference. It must
   contain no tenant data, credential, SQL, or driver error text.
5. If either the update or audit append fails, roll back both.
6. Restart/drain worker replicas through the normal rollout so each startup
   reloads and validates the registry. Do not leave a mixed old/new registry
   view indefinitely.

### Enable

Enable only when the owning feature's sweep bean, actor, transaction security
context, metrics, runbook, and singleton tests are deployed. After rollout,
require successful ticks within three configured intervals.

### Disable

Record why scheduled work may stop and the maximum safe disabled duration.
Drain active work at its batch boundary, apply the audited change, roll workers,
and confirm no later tick starts. Disabling auto-submit requires incident-level
approval because it suspends a mandatory exam-timing control.

### Change Interval

Confirm the new cadence fits database/worker capacity and the sweep's maximum
tick duration. Apply the audited value, roll workers, then verify observed tick
spacing, duration, lock contention, and backlog. Reverting the interval is a
second audited change, never an unaudited rollback.

## Verification

For every change, retain the command result, event identifier, before/after
row values, worker rollout revision, first successful tick, and relevant alert
state. The audit event is required because a registry mutation changes system
behavior outside ordinary tenant workflows and must remain attributable even
when operational logs expire.
