# TASK-PLAT5-DEFECT-005 Migration Job Classification Conflict

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-005`

## Baseline defect

Architecture section 17.6 step 2 describes the migration Job as expand-phase
only, while section 18.1 stage 15 says Migration Job then rolling update
without that qualifier. A later `CONTRACT` release also needs the controlled
Job, so the two statements cannot both be complete.

## Resolution adopted by this feature

Every release manifest declares exactly one classification: `EXPAND`,
`MIGRATE`, or `CONTRACT`. The build rejects absent, mixed, or inconsistent
classifications. The migration Job consumes that declaration for every schema
release, and rollback policy refuses a manifest classified `CONTRACT`.

## Next-baseline action

Replace "expand-phase only" with the one-classification-per-release contract,
while retaining the requirement that `CONTRACT` occurs only in a later release
and is never rolled back.
