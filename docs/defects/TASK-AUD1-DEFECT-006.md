# TASK-AUD1-DEFECT-006 Audit Ownership Table Omits Chain Anchors

Status: **OPEN FOR NEXT BASELINE - COMPLETE TABLE SET ADOPTED LOCALLY**

Owner: Architecture Owner

Raised by: `FEAT-AUD-001`

## Baseline Defect

Architecture section 9.2 lists only `audit_event` and `audit_chain_head` for
the `audit` schema. `ARC-AUD-005` and `ARC-AUD-007` additionally require a
checkpoint table, sealed-epoch evidence, and the per-tenant root-chain head.
Leaving those relations out makes schema ownership and grant review
incomplete.

## Resolution Adopted by This Feature

The `audit` platform capability owns exactly these principal relations:

| Relation | Purpose |
|---|---|
| `audit.audit_event` | Immutable attributable events and their shard-chain links |
| `audit.audit_chain_head` | Mutable head for each open tenant/class/period/shard chain |
| `audit.audit_chain_checkpoint` | Signed interim chain checkpoints |
| `audit.audit_chain_seal` | Permanently retained signed epoch roots and shard counts |
| `audit.audit_chain_root_head` | Compare-and-swap head of the per-tenant root chain |

All five remain owned by `app_migrator`; application access follows the signed
per-table decision from audit task `P0.4`. This correction adds no sixth data
store and does not transfer ownership to another module.

## Next-Baseline Action

Extend section 9.2 and its grant matrix with the three missing relations and
the approved per-table privileges. Keep the live grant-diff gate aligned with
that complete inventory.
