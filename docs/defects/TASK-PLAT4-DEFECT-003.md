# TASK-PLAT4-DEFECT-003 Cross-Tenant Relay Versus Forced RLS

Status: **RESOLVED IN FEAT-PLAT-004 DESIGN**

Owners: `FEAT-PLAT-004` and `FEAT-PLAT-002`

## Conflict

`outbox.outbox_event` carries `tenant_id`, so `ARC-DATA-018` requires enabled
and forced RLS with fatal missing context. The relay must drain rows for all
tenants, so the ordinary tenant-equality policy cannot authorize its work.

## Adopted Resolution

Keep forced RLS and define exactly two policies: the existing strict tenant
predicate for module-role inserts, and a policy scoped to
`app_outbox_relay` plus the closed transaction-local platform context
`app.platform_scope = 'outbox_relay'`. The relay has no `BYPASSRLS` or owner
privilege. Its first transaction statements are `SET LOCAL ROLE
app_outbox_relay` and `SET LOCAL app.platform_scope = 'outbox_relay'` through
the existing security-context seam.

The normative SQL, negative guarantees, and catalogue assertions are in
`docs/architecture/outbox-design.md` P2.4.
