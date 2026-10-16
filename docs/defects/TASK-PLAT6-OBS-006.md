# TASK-PLAT6-OBS-006: Control identifiers lack defining prose

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner.

## Baseline Gap

Matrix identifiers ARC-REL-008 and ARC-OBS-004 lack their own defining prose; shutdown/startup prose is labelled ARC-OPS-007 and ARC-OPS-004.

## Adopted Resolution

Treat the matrix labels as aliases for tracing existing shutdown/startup controls, with the defining ARC-OPS references governing implementation. Do not invent separate behavior or claim new verification identifiers.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/phase-1-discovery.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending Architecture Owner acknowledgement.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Resolve aliases or rename matrix rows consistently in the next baseline.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
