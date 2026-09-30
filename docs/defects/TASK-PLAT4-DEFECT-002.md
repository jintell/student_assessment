# TASK-PLAT4-DEFECT-002 Missing Outbox Maintenance Authority

Status: **OPEN - RAISED FOR NEXT BASELINE; LOCAL RESOLUTION APPROVED**

Owners: Architecture Owner and `FEAT-PLAT-002`

Raised by: `FEAT-PLAT-004`

## Baseline Defect

Section 11.2 requires published outbox partitions to be detached after seven
days, but `ARC-PLAT-007` reserves DDL ownership for `app_migrator` and limits
that identity to the migration entrypoint. PostgreSQL does not expose a
grantable table-level `ALTER` privilege, so direct attach/detach authority
cannot be expressed as an ordinary table grant.

## Resolution Adopted by This Feature

Create `app_outbox_maintenance` as a `NOLOGIN` role with no row-data
privileges. Grant it only `EXECUTE` on owner-controlled `SECURITY DEFINER`
routines that validate the target as a partition of `outbox.outbox_event`,
enforce the age and state guards, set a fixed safe `search_path`, and perform
attach or detach as `app_migrator`. Grant membership to `app_worker` only.
The role cannot read payloads or alter another relation.

## Owner Acknowledgement

The Architecture Owner and `FEAT-PLAT-002` owner acknowledge this as the
narrow implementation of the required attach/detach authority. The signed
acknowledgement is
`ci/dor/FEAT-PLAT-004/P0.5-role-amendment-acknowledgement.json`.

## Next-Baseline Action

Add the maintenance role, guarded-routine authority, membership, and denial
set to section 9.2 and the durable grant matrix. Preserve `app_migrator` as
the only DDL-owning identity and require negative tests for payload reads and
foreign-relation alteration.
