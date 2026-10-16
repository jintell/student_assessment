# TASK-PLAT6-DEFECT-008: Shutdown and containment references missing

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner.

## Baseline Gap

The runtime feature card omits ARC-OPS-007 for graceful shutdown and ARC-REL-003 for in-process containment despite owning the behavior.

## Adopted Resolution

Adopt both controls. docs/shutdown-contract.md specifies the implemented phase order and budget; runtime bulkheads, route limits/timeouts, reserved pools and memory policy implement containment. Numeric proposals remain distinguished from approvals.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/phase-4-backend-implementation.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending Architecture Owner acknowledgement; the published contract is implementation evidence, not a revised architecture approval.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Add the two normative references to the runtime card and preserve downstream live-load evidence.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
