# ADR-011A Atomic Audit Append

Status: APPROVED
Date: 2026-10-07
Amends: `ADR-011` / `ARC-AUD-005` at architecture baseline `arch-v1.4`
Findings: `REV11-ADR-GAP-001`, `REV11-ARCH-REVIEW-001`

## Context

The baseline inserts an immutable audit event before its transaction locks and
advances the shard head. Concurrent writers can therefore select the same
predecessor, insert sibling hashes, and only then discover the conflict. The
loser cannot remove or rewrite its event, so the chain has already forked.

Adding a separate lock statement to the baseline event-insert and head-upsert
pair would consume three audit statements and breach the six-statement answer
save contract. Deferring the head mutation until commit does not serialize
predecessor selection.

## Decision

Adopt `PRELOCKED_TWO_STATEMENT_APPEND_V1` on the caller's existing connection
and transaction:

1. **Lock predecessor.** After all business SQL, execute a keyed `SELECT ...
   FOR UPDATE` on the pre-provisioned `audit.audit_chain_head` row. Absence is
   a provisioning failure. The row returns the committed sequence and head
   hash. Canonical event bytes may be prepared earlier; only sequence binding
   and `SHA-256(previous_hash || canonical_event)` occur while the lock is held.
2. **Insert and advance atomically.** Execute one data-modifying CTE whose
   materialized insert writes `audit.audit_event` and whose dependent final
   `UPDATE` advances the locked head only when its observed sequence and hash
   still match. Exactly one updated head row is required. Zero or multiple
   rows is an integrity error that propagates and rolls back the caller's
   transaction.

The final SQL statement inserts the event before advancing the head, but both
effects belong to one PostgreSQL statement and one transaction. A failure,
cancellation, timeout, business rollback, or process loss before commit leaves
neither effect. A second same-shard writer blocks at step 1 and, after the
winner commits, receives that winner's hash as its predecessor.

Open chain-head rows are provisioned before writes are enabled: initial rows
through `FEAT-TENANT-001`, and future UTC-month rows alongside the audit
partition-creation job. Lazy first-write creation is forbidden.

## Statement Budget

Answer save remains exactly six database statements. Its two audit statements
are now:

1. pre-provisioned shard-head `SELECT ... FOR UPDATE`; and
2. event-insert plus conditional head-advance data-modifying CTE.

No function, trigger, advisory lock, or uncounted database call hides work from
the query counter. The connection enters an audit-finalization phase before
step 1; after step 2, non-audit SQL is refused until commit or rollback.

## Grant Amendment

For approved module and composite emitter roles,
`audit.audit_chain_head` changes from `INSERT, UPDATE` to `SELECT, UPDATE`.
`INSERT` is removed because only provisioning identities create head rows.
`DELETE`, `TRUNCATE`, ownership, and schema-wide mutation remain denied.
`audit.audit_event` remains `INSERT` only with its update/delete trigger.

This narrowly supersedes the chain-head row in the signed `P0.4` grant
decision. All other signed grants and denials remain unchanged.

## Consequences

- Same-shard predecessor selection is linearizable before an immutable event
  exists; sibling hashes cannot be produced by legitimate concurrency.
- The row lock is held only for hash finalization, the second statement, and
  commit. A6 still measures lock wait and audit share at the approved load.
- Missing pre-provisioned heads fail closed instead of silently creating an
  ungoverned chain topology.
- The application retains pure chain algebra; PostgreSQL coordinates ordering
  but does not implement canonicalization or hashing.

## Rejected Alternatives

- A third `SELECT ... FOR UPDATE`: correct but exceeds the existing budget
  when added to the baseline two statements.
- A database function or trigger: hides statement cost and duplicates domain
  hashing or canonicalization inside the persistence boundary.
- An advisory lock: collision and lifecycle semantics are weaker than the
  authoritative chain-head row.
- Insert first and retry/repair later: incompatible with immutable evidence
  and the preserve-never-repair rule.

## Verification

Integration verification must force same-shard contention and inject failure
after each statement and before commit. It asserts a dense sequence, each
`prev_hash` equal to the preceding committed `record_hash`, no sibling, no
orphan event, no advanced head without an event, exactly two audit statements,
and refusal of any later non-audit SQL.

The signed approval record is
`ci/dor/FEAT-AUD-001/P2.16-atomic-append-approval.json`.
