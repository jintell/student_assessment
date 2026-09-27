# TASK-PLAT2-DEFECT-002 Outbox Table Name Mismatch

Status: **OPEN - RAISED FOR NEXT BASELINE**

Owner: Architecture Owner

Raised by: `FEAT-PLAT-002`

## Baseline Defect

Architecture section 9.2, the grant matrix, and `ARC-EXAM-014` name the durable
table `outbox.outbox_event`, while the section 8.4 `OutboxWriter` example names
`outbox.event`. Implementing both names would split one platform contract and
invalidate generated grants.

## Resolution Adopted by This Feature

The canonical name is `outbox.outbox_event`. The ownership map, executable
grant matrix, default privileges, composite-role grants, documentation, and
verification gates all use that name.

## Next-Baseline Action

Replace `outbox.event` in the architecture API example and any derived text
with `outbox.outbox_event`. State that the table is owned by the outbox
platform capability and created by its owning feature under `app_migrator`.
