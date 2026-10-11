# TASK-PLAT6-DEFECT-002 Unmodelled Advisory-Lock Registry

Status: **CLOSED - ADDITIVE TABLE APPROVED**

Owners: Architecture Owner and `FEAT-PLAT-002`

Raised by: `FEAT-PLAT-006`

Date closed: 2026-10-11

## Baseline Defect

Architecture section 9.2 assigns the advisory-lock registry to the `platform`
schema but omits the registry table from that schema's table list. Section 8.2
requires locks keyed by sweep name without defining the durable key registry.

## Adopted Resolution

Add `platform.sweep_registry` as an additive, non-tenant-scoped table delivered
by `FEAT-PLAT-006` and owned by `app_migrator`. Its agreed shape records the
sweep name, unique stored lock key, owning module and feature, default interval,
enabled state, system actor, and creation/update timestamps.

The later migration must register the table in `FEAT-PLAT-002`'s platform-table
catalogue and may not change schema ownership or introduce a tenant bypass.

## Owner Acknowledgement

Architecture Owner Josiah Adetayo and `FEAT-PLAT-002` owner Francis Okechukwu
approved this additive table in
`ci/dor/FEAT-PLAT-006/P0.6-registry-budget-approval.json`. Their detached
signatures are verified by `ci/verify-runtime-registry-budget-approval`.

Any wider scope or ownership change requires renewed approval.
