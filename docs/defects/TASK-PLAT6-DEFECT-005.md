# TASK-PLAT6-DEFECT-005: Runtime CI gates absent from feature card

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner; Plan Owner.

## Baseline Gap

The feature card does not allocate numbered blocking gates to its isolation, singleton, shutdown and image obligations.

## Adopted Resolution

Assign conformance to stage 4, per-role contexts to 7, PostgreSQL singleton/context/shutdown to 8, image/signing to 14 and deployed statelessness to 16. Stages 4/7/8 are wired; 14/16 evidence remains blocked.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/P7.24-suite-and-mutation-gate.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending acknowledgements from both owners; implemented gate wiring does not imply baseline acceptance.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Name the gate allocation in the feature card while retaining stage-14 and stage-16 blockers.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
