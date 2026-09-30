# TASK-PLAT4-OBS-002 Conflicting PostgreSQL Major

Status: **OPEN - RAISED FOR NEXT BASELINE; AUTHORITATIVE VERSION RECORDED**

Owners: Architecture Owner and `FEAT-PLAT-002`

Raised by: `FEAT-PLAT-004`

## Baseline Conflict

Architecture v1.4 sections 9.1 and 12.1 name PostgreSQL major 16. The later
Architecture Owner decision in
`docs/decisions/P0.5-postgresql-version-approval.md` selects major 17 for
local development, CI, staging, and production. Relay locking, `SKIP LOCKED`,
RLS, and partition-maintenance evidence must run against one authoritative
major.

## Authoritative Decision Consumed

PostgreSQL major 17 is authoritative. `FEAT-PLAT-004` consumes the immutable
image declared by `postgresqlImage` in `gradle.properties` and introduces no
version property, image tag, or digest of its own. A future major-version
change requires the architecture approval and compatibility validation named
in the existing decision.

## Next-Baseline Action

Correct sections 9.1 and 12.1 to major 17 and reference the approved immutable
image policy. Keep `verifyPostgresqlBaseline` blocking so a conflicting image
or documented major fails before migrations or integration tests run.
