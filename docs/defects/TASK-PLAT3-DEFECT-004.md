# TASK-PLAT3-DEFECT-004 Outbox and Audit Table Name Conflict

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-003`

## Baseline Defect

Architecture section 8.4 names `outbox.event` and `audit.event`. Architecture
section 9.2, the schema ownership model, migrations, and the approved grant
matrix use `outbox.outbox_event` and `audit.audit_event`. A port or grant built
from the section 8.4 names would target relations that do not exist.

## Resolution Adopted by This Feature

The `OutboxWriter` contract and all handover documentation use
`outbox.outbox_event`, matching the actual schema, migrations, and `INSERT`
grant. Audit handovers use `audit.audit_event`. No compatibility aliases or
duplicate tables were introduced.

## Next-Baseline Action

Correct the section 8.4 shared-kernel table and every derived diagram or
example to use `outbox.outbox_event` and `audit.audit_event`. Treat section 9.2
and the grant matrix as authoritative until the corrected baseline is issued.
