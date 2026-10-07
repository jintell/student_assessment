# TASK-AUD1-BLOCKER-001 Audit Chain Grants Conflict With Grant Matrix

Status: **RESOLVED - PER-TABLE AMENDMENT APPROVED**

Owners: Architecture Owner, Security Engineer, and `FEAT-PLAT-002` owner

Raised by: `FEAT-AUD-001`

## Baseline Conflict

The signed section 9.2 grant matrix and the persistence verification suite
permit application roles to insert into future `audit` tables but prohibit
`UPDATE` and `DELETE` throughout the schema. The audit chain cannot implement
its ratified concurrency protocol under that rule:

- `audit.audit_chain_head` requires `INSERT ... ON CONFLICT DO UPDATE` inside
  the business transaction; and
- `audit.audit_chain_root_head` requires a compare-and-swap `UPDATE` by the
  sealer.

Applying either statement without an approved per-table amendment would make
the live grant set fail the existing persistence gates. Omitting the grants
would make every audited mutation or epoch seal fail at runtime.

## Proposed Per-Table Resolution

The audit event remains immutable while mutable coordination state receives
only the statements its protocol requires:

| Table | Proposed write authority |
|---|---|
| `audit.audit_event` | `INSERT` only for module and approved composite roles; no application `UPDATE` or `DELETE`; table-scoped trigger backstop |
| `audit.audit_chain_head` | `INSERT, UPDATE` for module and approved composite roles; no `DELETE` |
| `audit.audit_chain_checkpoint` | `INSERT` only for the background audit workload |
| `audit.audit_chain_seal` | `INSERT` only for the background audit workload |
| `audit.audit_chain_root_head` | Compare-and-swap `UPDATE` only for the sealer role |
| Expired audit-event partitions | `DELETE` only for the retention role, never an application role |

No schema-wide mutation grant is proposed. Checkpoints, seals, and audit
events remain non-updatable, and the application receives no deletion path.

## Required Persistence Amendments

The `FEAT-PLAT-002` owner must agree changes that preserve its negative
security assertions while expressing the per-table exception:

1. `P7.8` must compare grants per table instead of asserting that no module
   role has `UPDATE` anywhere in `audit`.
2. `P7.15` must continue proving that default privileges grant only `INSERT`,
   while allowing a later audited migration to add the explicit
   `audit_chain_head` grant.
3. The composite-role exclusion assertion must likewise distinguish immutable
   event/evidence tables from the chain-head coordination table.

## Approval Resolution

`ci/dor/P0.3-dor-approval.json` states that every material section 9.2 change
requires renewed Architecture Owner and Security approval. This proposal also
requires acknowledgement from the `FEAT-PLAT-002` owner because it changes
that feature's blocking verification contract.

The exact table/statement matrix and all three persistence-test amendments are
approved in:

- `ci/dor/FEAT-AUD-001/P0.4-audit-grant-amendment-approval.json`;
- its Architecture Owner and Security Engineer detached signatures; and
- the `FEAT-PLAT-002` owner acknowledgement signed by the Engineering Lead.

`ci/verify-audit-grant-amendment-approval` verifies the complete decision,
trusted signer fingerprints, and Architecture/Security separation. Tasks
`P2.5` and `P3.8` may now implement only the approved per-table model; any
wider grant requires renewed approval under the signed change policy.
