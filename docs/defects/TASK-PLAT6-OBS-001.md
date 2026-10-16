# TASK-PLAT6-OBS-001: Role and scheduler observability gaps

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner; FEAT-OBS-001 Owner; FEAT-OPS-004 Owner.

## Baseline Gap

Architecture §16 omits role/resource fields, scheduler tick/lock/staleness metrics and concurrent-sweep/grace-period alert conditions demanded by the card.

## Adopted Resolution

Register bounded role/sweep telemetry and retain P9.4/P9.5/P9.6 alert proposals plus the P9.7 dashboard-6 request. Auto-submit backlog remains owned by FEAT-DLV-004; proposals do not mean deployed alerts.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/P9.3-runtime-role-telemetry.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending acknowledgements from all three owners; repository handoff artifacts exist but no owner receipts for this record are retained.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Reconcile §16 and accept or revise the alert/dashboard proposals with explicit deployment ownership.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
