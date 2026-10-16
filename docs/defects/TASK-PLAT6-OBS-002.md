# TASK-PLAT6-OBS-002: Reserved route spelling differs

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner.

## Baseline Gap

Architecture §8.2 omits /api/v1 from the path that §10.2 and §17.4 specify.

## Adopted Resolution

Use exactly GET /api/v1/exam-sessions/{id}/pin-distribution in manifests and boot validators. Until FEAT-EXAM-003 is delivered, no business route is required or implemented.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/phase-2-architecture-design.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending Architecture Owner acknowledgement.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Make the reserved template identical in all baseline sections.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
