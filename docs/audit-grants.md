# Audit grants: immutable evidence, mutable coordination

The prohibition is **table-scoped**, not schema-scoped. Updating a chain head
advances coordination state; it does not update the immutable audit event.
Schema-wide `UPDATE` would expose evidence, while a schema-wide denial would
break atomic append and root CAS. Neither is the approved model.

Authority: [TASK-AUD1-BLOCKER-001](defects/TASK-AUD1-BLOCKER-001.md), the signed
[P0.4 amendment](../ci/dor/FEAT-AUD-001/P0.4-audit-grant-amendment-approval.json)
and its later [ADR-011A narrowing](decisions/ADR-011A-atomic-audit-append.md).
The original proposed head `INSERT, UPDATE` is superseded by `SELECT, UPDATE`:
ordinary emitters lock pre-provisioned rows, never lazily create them.

## Per-table authority

| Relation | Permitted authority | Required denial / boundary |
|---|---|---|
| `audit.audit_event`, including leaves | Module and approved composite roles: `INSERT` | No application `UPDATE`, `DELETE` or `TRUNCATE`; no general reader grant |
| `audit.audit_chain_head` | Emitters: `SELECT, UPDATE`; sealer: `SELECT` | No emitter `INSERT` or `DELETE`; keyed lock and atomic append only |
| `audit.audit_chain_checkpoint` | `app_audit_sealer`: `INSERT` | No application insertion or evidence mutation |
| `audit.audit_chain_seal` | `app_audit_sealer`: `SELECT, INSERT` | No update/delete; insert commits only with winning root CAS |
| `audit.audit_chain_root_head` | `app_audit_sealer`: `SELECT, UPDATE` | No ordinary application mutation; CAS predicate is mandatory |
| `audit.audit_shard_policy` | Partition-maintenance identity: `SELECT, INSERT` | Policy changes append at approved epoch boundaries, not in-place rewrites |
| `audit.audit_disposition_lifecycle` | Retention identity: `SELECT, INSERT, UPDATE` | Mutable workflow state is not the event trail; ordinary emitters cannot write it |
| Expired event leaves | Only approved retention authority, exact eligible relation and operation | No blanket application or schema-wide deletion; holds and whole-leaf eligibility still apply |

Provisioning is separate: `app_audit_partition_maintenance` receives execution
of narrowly scoped month/head/root provisioning functions, not general ownership
or DDL. Signer workload identity/KMS authority is separate from database grants.
Do not treat every worker as a signer or retention administrator.

The [machine grant matrix](../src/main/resources/db/grants/grant-matrix.json),
[audit migrations](../src/main/resources/db/migration/audit) and
[repeatable grant application](../src/main/resources/db/migration/platform/R__apply_grant_matrix.sql)
are the executable sources. V10 adds the event `BEFORE UPDATE OR DELETE`
exception trigger and explicit grants; later migrations secure tenancy and
lifecycle state. Grants are the first barrier; the trigger is a second barrier,
not a substitute for privilege denial. The trigger does not make `TRUNCATE`
safe. Ownership/migration identities must not be used on request paths.

## Persistence contract and deployment limits

FEAT-PLAT-002 P7.8 compares privileges **per table**, including effective
composite-role authority. P7.15 continues proving default privileges are
`INSERT`-only; explicit audited migrations/repeatable enforcement narrow or
extend individual relations. Default privileges must not silently authorise
future immutable evidence tables. Negative tests must continue rejecting
event mutation and cross-tenant access, not merely check that head updates work.

The older general [grant guide](grant-matrix.md) contains schema-wide wording;
for audit, the signed amendments and per-table executable matrix supersede
that blanket wording. Do not broaden grants to reconcile prose.

Retention's approved expired-leaf exception does not establish a production
detach/archive/drop orchestrator or permit bypassing the event trigger.
Compliance-reader authority is still unresolved in
[TASK-AUD1-BLOCKER-002](defects/TASK-AUD1-BLOCKER-002.md); no read-all or owner
connection workaround is permitted. New grants or weakened immutable-table
denials require renewed Architecture/Security approval and persistence-owner
acknowledgement. Never rewrite an applied migration to change this contract.
