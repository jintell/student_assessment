# TASK-PLAT4-DEFECT-001 Missing Outbox Relay Role

Status: **OPEN - RAISED FOR NEXT BASELINE; LOCAL RESOLUTION APPROVED**

Owners: Architecture Owner and `FEAT-PLAT-002`

Raised by: `FEAT-PLAT-004`

## Baseline Defect

The section 9.2 grant matrix gives module roles only `INSERT` on
`outbox.outbox_event`, while pool login roles hold no direct object grants.
The section 11.2 relay must select and lock pending rows and update their
state, so no approved role can execute the required drain operation.

## Resolution Adopted by This Feature

Create `app_outbox_relay` as a `NOLOGIN` role with `USAGE` on `outbox` and
`SELECT, UPDATE` on `outbox.outbox_event`. Grant membership to `app_worker`
only. The role receives no `INSERT` or `DELETE`, no ownership, and no grant on
any business schema. `app_api` and `app_pindist` receive no membership.

## Owner Acknowledgement

The Architecture Owner and `FEAT-PLAT-002` owner acknowledge this as an
additive, narrower-than-module-role amendment. The signed acknowledgement is
`ci/dor/FEAT-PLAT-004/P0.5-role-amendment-acknowledgement.json`.

## Next-Baseline Action

Add `app_outbox_relay` and its exact membership and denial set to section 9.2
and the durable grant matrix. Keep the live grant-diff and negative privilege
tests blocking.
