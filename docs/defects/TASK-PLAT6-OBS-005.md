# TASK-PLAT6-OBS-005: Plan architecture versions disagree

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner; Plan Owner.

## Baseline Gap

Universal DoR refers to v1.3 while Phase 0 entry and L11 use v1.4 at arch-v1.4.

## Adopted Resolution

Use the signed v1.4 tag/commit pinned by ci/architecture-ratification.json and runtime DoR approvals. Preserve historical records; do not silently rewrite their baseline references.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/phase-0-gate-prerequisites.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending acknowledgements from both owners for this version-reconciliation record; existing v1.4 approval is not approval of a plan amendment.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Reconcile current normative plan version references while retaining historical provenance.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
