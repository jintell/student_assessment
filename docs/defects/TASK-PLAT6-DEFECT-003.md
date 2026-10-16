# TASK-PLAT6-DEFECT-003 Worker Lock-Connection Arithmetic

Status: **CLOSED - TWO-PERMIT CAP APPROVED**

Owners: Architecture Owner and `FEAT-PLAT-002`

Raised by: `FEAT-PLAT-006`

Date closed: 2026-10-11

## Baseline Defect

Architecture sections 15.2 and 17.1 publish a ten-connection worker pool: four
for grading and six shared by the outbox relay, sweepers, and notification
dispatcher. Allowing every sweep to hold a session-level lock concurrently
could consume the six shared connections and starve other background work.

## Adopted Resolution

Keep the published worker pool at ten and cap lock-holding concurrency at two
permits per replica: one for the serialized sweep runner and one for the outbox
relay. The four grading connections remain separate; at most two of the six
background connections hold locks, leaving four for the dispatcher and other
background work.

This is a concurrency bound inside the existing allocation, not a pool-budget
increase. A third permit or a larger pool requires renewed approval and a
re-derived CI stage 4a envelope.

## Owner Acknowledgement

Architecture Owner Josiah Adetayo and `FEAT-PLAT-002` owner Francis Okechukwu
approved the arithmetic in
`ci/dor/FEAT-PLAT-006/P0.6-registry-budget-approval.json`. Their detached
signatures are verified by `ci/verify-runtime-registry-budget-approval`.

## P10.8 Baseline Revision Handoff — 2026-10-16

The original Architecture Owner and FEAT-PLAT-002 Owner approvals above remain
in force; their verifier passed on 2026-10-16. Publish this adopted resolution
in the next baseline. No wider scope or new approval is implied.

Current implementation: [connection-envelope evidence](../evidence/FEAT-PLAT-006/P8.5-connection-envelope.md).
