# TASK-PLAT6-OBS-004: Pindist missing from rollout order

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner.

## Baseline Gap

Architecture §17.6 names API and worker rollout but omits pindist; the migration process also needs distinguishing from the three runtime roles.

## Adopted Resolution

Propose step 6b for pindist after API and before worker, subject to FEAT-OPS-007 orchestration review. cbt-migrate stays a separate migration-only process, not a fourth runtime role.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/P8.6-pindist-rollout-amendment.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending Architecture Owner acknowledgement; P8.6 is an amendment proposal, not approved deployment ordering.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Resolve the proposed step 6b and cross-reference the migration-first constraint.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
