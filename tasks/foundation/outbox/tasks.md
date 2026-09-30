# Task List — `FEAT-PLAT-004` ★ Transactional Outbox and Broker Relay

## Overview

|                       |                                                                                                                                                                                                                                                                                       |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-PLAT-004`), §8.0 (universal DoR/DoD), §10 Phase 0, §11.2 outbox-and-audit track, §14.2, §14.4, §19                                                                                                                                                 |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §9.2, §11.1, §11.2 (`ARC-PLAT-011`), §11.3 (`ARC-PLAT-012`), §11.4, §14.4, §14.6, §16.2, §16.3, §16.4, §17.1, §18.1 stages 4/8/9/10/18, §19.7, §19.8, §23.7, `ADR-009`, `ADR-023`, `ARC-DATA-018`, `ARC-DATA-026`, `ARC-DATA-027` |
| Delivery phase        | Phase 0 — Engineering Foundation                                                                                                                                                                                                                                                      |
| Dependencies          | `FEAT-PLAT-002` (`outbox` schema, `app_migrator` ownership, `ALTER DEFAULT PRIVILEGES` `INSERT` bootstrap, RLS convention and its catalogue gate), `FEAT-PLAT-003` (`OutboxWriter` port, `TenantId`, `ActorContext`, controlled clock, correlation identifier). **Undeclared in the plan and adopted as a seam:** `FEAT-PLAT-006` (scheduler singleton, `worker` role) — `TASK-PLAT4-DEFECT-004` |
| Consumed by           | `FEAT-OBS-001` (trace propagation across the broker), `FEAT-TENANT-001`, `FEAT-GRD-002`, `FEAT-NOTF-001`, `FEAT-DLV-002`, `FEAT-RSLT-002`, `FEAT-CORR-003`, `FEAT-OPS-003`, and every later feature that publishes an integration event |
| Generated on          | 2026-09-03                                                                                                                                                                                                                                                                            |
| Methodology           | Clean architecture. The `OutboxWriter` **port** already lives in `shared.kernel` (`FEAT-PLAT-003 tasks.md` `P2.5`, `P4.5`); this feature adds **only** `platform.infra` adapters — writer, relay, topology, consumer dedupe — plus migrations. No `domain` type is added, no module is imported, no service layer is introduced. Dependencies point inward only |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                                                                               |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                                                                                 |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                                                                            |

**Objective.** Make every asynchronous cross-module and outbound side effect an `outbox.outbox_event` row
written in the same transaction as the business change, relay it to RabbitMQ at least once, and make every
consumer idempotent on stable business identity — so that no accepted write is lost and no redelivery
causes a duplicate effect.

**Why this feature is `★`.** It is the mechanism that turns "the event happened if and only if the state
changed" from probable into true. `NFR-REL-001` admits no lost accepted write; `NFR-REL-003` requires
duplicate-prone operations to be idempotent; and the two consequences are concrete — a broker redelivery
must not send a candidate a second PIN or OTP (`DEP-002` cond. 2/9) and a grading reprocess must not
publish a second authoritative result (`REQ-RSLT-038`). Eight later features publish through it, so a
defect here is a defect in all of them.

### Confirmed implementation decisions

| Decision                        | Choice                                                                                                                                                                                                              | Consequence                                                                                                                                                              |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Layer placement                 | Adapters only, in `platform.infra`. The port is `FEAT-PLAT-003`'s and is not redefined here                                                                                                                        | The outbox is infrastructure. A `domain` type that knew about RabbitMQ would make R1–R8 unenforceable for every module that publishes                                     |
| Write discipline                | `OutboxWriter.append(...)` joins the caller's transaction and has **no** connection- or template-supplying overload                                                                                                 | An out-of-transaction outbox write is not merely discouraged, it is inexpressible. `ARC-PLAT-011` becomes a type-level guarantee rather than a review item                |
| Relay identity                  | A new narrow role `app_outbox_relay` with `SELECT, UPDATE` on `outbox.outbox_event` only, granted as membership to **`app_worker` alone**                                                                            | §9.2 grants no role the ability to read the outbox, so the relay could not run at all — `TASK-PLAT4-DEFECT-001`. `app_api` and `app_pindist` never gain drain privilege   |
| Relay semantics                 | Advisory-locked singleton on the background role; 200 ms tick; `ORDER BY created_at … FOR UPDATE SKIP LOCKED LIMIT 200`; `PENDING → CLAIMED (claim_expires_at = now()+30s) → PUBLISHED`; publisher confirms          | §11.2 verbatim. Publication is **sequential within a batch** so oldest-first drain is real                                                                                |
| Stale-claim reclamation         | A `CLAIMED` row past `claim_expires_at` returns to `PENDING`, counted                                                                                                                                              | §11.2 defines the claim but not its expiry path. Without reclamation a relay crash strands rows silently — the exact failure `NFR-REL-001` forbids                        |
| Poison handling                 | `attempt_count` on the row; at 8 failed publications the row becomes `FAILED` with an operator alert and never blocks the head                                                                                       | §11.2. `FAILED` is a terminal operator state, not a retry state — a row is redriven by an audited action, not by the relay                                                |
| Tunable without deploy          | Batch size and tick interval read from `platform.platform_config`, bounds-validated at load, refusing an out-of-range value                                                                                        | `ARC-RISK-014`'s stated mitigation is "tunable relay batching without deploy". `ARC-VERIFY-018` still holds because the bounds are enforced                               |
| Consumer idempotency            | One `processed_event` table **per consuming module schema**, unique on `outbox_event_id`, applied with the consumer's own §14.6 business key in the same transaction as the effect                                   | Module roles hold no `SELECT` on `outbox`, so a shared dedupe table in `outbox` would be unreadable. Per-schema keeps the grant matrix intact                             |
| Ordering                        | **No ordering is relied upon.** Consumers are idempotent and version-guarded, proved by an out-of-order delivery test. A consistent-hash exchange is documented as an unused seam                                    | §11.2's "single consumer per key" is not deliverable on a shared quorum queue with prefetch 32 — `TASK-PLAT4-DEFECT-006`. §11.2 itself claims no global order and resolves `REQ-IAM-018` in-transaction, so nothing needs it |
| Event contract                  | `<context>.<Event>.v<n>`; one JSON Schema per type-version committed under `contracts/events/` as the registered baseline; a Gradle compatibility checker BLOCKS CI stage 9                                          | §11.3 requires "a schema-registry check in CI" but names no technology. An in-repo baseline adds no production component and keeps `ARC-REL-001` (candidate path on PostgreSQL only) intact |
| Unhandled version               | Dead-lettered with a **distinct** alert, never silently dropped                                                                                                                                                    | §11.3 verbatim. A silent drop is a lost accepted write wearing a successful ack                                                                                            |
| Answer submission is excluded   | A conformance rule asserts the answer-acceptance path writes **no** outbox row                                                                                                                                     | `ADR-009` excludes the hottest path deliberately (`ARC-PLAT-006`); the rule stops a later feature reintroducing the insert and quietly costing `NFR-PERF-001`              |
| `ADR-023` exception             | A cross-schema write is legal only under a composite role on the closed enumerated list; everything else is `OutboxWriter`. Asserted against `pg_roles`                                                              | Scope item (f). The exception stays enumerated rather than becoming an implicit alternative — `ARC-RISK-022`                                                               |
| Correlation and trace carriage  | `correlation_id` and W3C trace context are **columns** on the row and **headers** on the message                                                                                                                    | `FEAT-OBS-001` depends on this feature for propagation. This feature ships the carrier; the end-to-end join test is `FEAT-OBS-001`'s                                       |
| Retention                       | Range partitioning by `created_at` with a 7-day detach, driven by a narrow `app_outbox_maintenance` role                                                                                                            | §11.2 prunes "by partition detach", which is DDL — and only `app_migrator` has DDL, used only by the migration entrypoint. See `TASK-PLAT4-DEFECT-002`                     |

### Assumptions

1. **The `outbox` schema, its `app_migrator` ownership and the `INSERT` default-privilege bootstrap already
   exist**, delivered by `FEAT-PLAT-002 tasks.md` `P3.7` and `P3.9`. This feature creates the table and receives the
   `INSERT` grant by construction, with no back-fill task.
2. **The table is `outbox.outbox_event`**, per §9.2, the grant matrix and `TASK-PLAT2-DEFECT-002` — not
   §8.4's `outbox.event`. `FEAT-PLAT-003 tasks.md` assumption 4 already carries this resolution forward.
3. **The `OutboxWriter` port, `TenantId`, `ActorContext`, the controlled clock and the ULID correlation
   identifier are `FEAT-PLAT-003`'s types** and are consumed, not redefined.
4. **This feature publishes no business event.** Every event belongs to its owning feature (plan §8.1
   out-of-scope). What ships here is the table, the writer, the relay, the `integration.*` topology, the
   contract discipline and one reference consumer used only by tests.
5. **RabbitMQ uses durable quorum queues** (§11.4, §12). Broker provisioning as infrastructure is Platform
   Ops'; queue and exchange *declaration* is this feature's.
6. **The PostgreSQL image is whatever `FEAT-PLAT-002` pins.** This feature pins no version of its own — see
   `TASK-PLAT4-OBS-002`.
7. **CI stage 8 already has a RabbitMQ Testcontainer in its declared contents** (§18.1); this feature is the
   first to actually use it and therefore adds the harness.

### Blockers and defects carried into this task list

| ID                      | Statement                                                                                                                                                                                                                                                                                                                                                                                              | Owning task            |
|-------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| `PLAN-BLOCKER-001`      | `ci/architecture-ratification.json` is `status: RATIFIED`. Per plan §10 Phase 0 entry criteria this gates Phase 0 **implementation**. Discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7`; not restated here                                                                                                                                                                                                    | `P0.1`                 |
| `TASK-PLAT4-DEFECT-001` | **No role in the §9.2 grant matrix can operate the relay.** `app_<module>` holds `INSERT` on `outbox.outbox_event` only; the pool login roles hold no direct object grants at all (`ARC-DATA-026`). The relay needs `SELECT … FOR UPDATE SKIP LOCKED` and `UPDATE`. A narrow `app_outbox_relay` role is shipped here and the matrix omission raised to `FEAT-PLAT-002` and the Architecture Owner        | `P0.5`, `P2.3`, `P3.4`, `P10.10` |
| `TASK-PLAT4-DEFECT-002` | **7-day pruning by partition detach is DDL, but `ARC-PLAT-007` reserves DDL to `app_migrator` used only by the migration entrypoint.** Retention would then depend on release cadence rather than on age. A narrow `app_outbox_maintenance` role with attach/detach on `outbox.outbox_event` only is shipped here and raised as an architecture amendment                                                | `P0.5`, `P2.11`, `P3.5`, `P10.10` |
| `TASK-PLAT4-DEFECT-003` | **RLS and a cross-tenant relay conflict.** `FEAT-PLAT-002 tasks.md` `P2.6`, `P2.7` and `P4.12` make RLS plus `FORCE ROW LEVEL SECURITY` unskippable for any table carrying `tenant_id`, and `ARC-DATA-018` uses `current_setting(…, false)` so an absent setting is fatal. The relay is cross-tenant by construction. Resolved with two policies — tenant-predicate for module roles, role-scoped for `app_outbox_relay`    | `P2.4`, `P3.3`, `P7.9` |
| `TASK-PLAT4-DEFECT-004` | **Undeclared dependency on `FEAT-PLAT-006`.** §11.2 describes the relay as "worker, advisory-locked" and `FEAT-PLAT-006` owns scheduler singletons and the advisory-lock registry, but the plan's dependency row names only `FEAT-PLAT-002` and `-003`, and §11.2 runs the two on parallel tracks. The relay ships its own `pg_advisory_xact_lock` acquisition and adopts the registry at a stated seam   | `P0.6`, `P4.6`, `P7.17` |
| `TASK-PLAT4-DEFECT-005` | **Verification-register mis-citation.** Plan §14.4 assigns `ARC-VERIFY-012/013/014` to this feature as "outbox, event compatibility, idempotency inventory". §19.8 defines `-012` as the OpenAPI breaking-change diff, `-013` as the `ProblemDetail` fault-injection suite (`FEAT-PLAT-003`, already claimed by `TASK-PLAT3-DEFECT-003`) and `-014` as the webhook control set (`FEAT-NOTF-003`). This feature owns the **integration limb of `ARC-VERIFY-006`**; **no register identifier covers event-schema compatibility or the §14.6 inventory**, and the gap is raised rather than filled with an invented identifier | `P1.5`, `P10.10`       |
| `TASK-PLAT4-DEFECT-006` | **Per-aggregate ordering is not deliverable as written.** §11.2 claims it "via a partition key on `aggregate_id` and a single consumer per key", but §11.4 gives `integration.<context>` a single shared quorum queue at prefetch 32. §11.2 also states no global ordering is claimed and that `REQ-IAM-018` precedence is resolved in-transaction, so no MVP requirement needs order. Adopted: consumers are order-insensitive, idempotent and version-guarded, proved by an out-of-order test; a consistent-hash exchange is documented as an unused seam | `P2.7`, `P4.11`, `P7.8`, `P10.10` |
| `TASK-PLAT4-OBS-001`    | §16.4 defines exactly one outbox alert (backlog `PENDING` > 5,000 or oldest > 5 min, P2) and §16.2 has **no outbox row at all**. Relay throughput, publish-confirm failures, `FAILED` depth, reclaimed stale claims, relay tick liveness, `integration.dlq` depth and unhandled-version dead-letters are invisible. Seven metrics proposed and raised to `FEAT-OBS-001` / `FEAT-OPS-004`                | `P9.1`–`P9.4`, `P10.10` |
| `TASK-PLAT4-OBS-002`    | **PostgreSQL version conflict.** `architecture.md` §9.1 diagram and §12.1 both name **PostgreSQL major 16**, while `FEAT-PLAT-002 tasks.md` `TASK-PLAT2-DEFECT-005` pinned **17** on the stated grounds that no version appears anywhere in the baseline. One of the two statements is wrong; `SKIP LOCKED` and partition-detach semantics are version-sensitive. Flagged for correction; this feature pins nothing    | `P0.7`, `P10.10`       |
| `TASK-PLAT4-OBS-003`    | The CI stage 10 secret-leak scan covers logs, audit payloads and error responses but **not event payloads** — which, given `DEP-002` cond. 2/9, is precisely where a PIN or OTP would leak. Extension proposed to `FEAT-SEC-001`                                                                                                                                                                        | `P6.5`, `P10.10`       |

---

# Phase 0 – Gate Prerequisites

`PLAN-BLOCKER-001` is discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7` and is **not** restated. Under a
`temporaryArchitectureGate` (`implementationAllowed: false`) only Phase 1 and Phase 2 tasks are authorised.

1. [*] Confirm `FEAT-PLAT-001 tasks.md` `P0.5` or `P0.6` has completed and record which authorisation scope is in force. Deliverable: one-line entry in the phase log naming the tasks unblocked. Acceptance: no Phase 3+ task starts under `implementationAllowed: false`.
2. [*] Confirm `FEAT-PLAT-002` has delivered the `outbox` schema owned by `app_migrator` and the `ALTER DEFAULT PRIVILEGES … GRANT INSERT` bootstrap. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-002 tasks.md` `P3.7`, `P3.9`; verified by `FEAT-PLAT-002 tasks.md` `P7.15`.
3. [*] Confirm `FEAT-PLAT-003` has delivered the `OutboxWriter` port, `TenantId`, `ActorContext`, the controlled clock and the ULID correlation identifier. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-003 tasks.md` `P4.5`, `P4.1`–`P4.3`.
4. [*] Obtain agreement on the §14.6 idempotency inventory and the §11.3 event-evolution rules as this feature's additional Definition of Ready. Deliverable: signed DoR record naming both artifacts.
5. [*] Escalate `TASK-PLAT4-DEFECT-001` and `TASK-PLAT4-DEFECT-002` to the Architecture Owner and the `FEAT-PLAT-002` owner **before** authoring any migration, since both add roles to a matrix another feature owns. Deliverable: two defect records with the adopted resolution and an acknowledgement. Acceptance: the two new roles are accepted as additive and narrower than any existing role, or an alternative is directed.
6. [*] Record the `FEAT-PLAT-006` relationship explicitly: this feature ships its own advisory-lock acquisition now and adopts the registry later. Deliverable: adoption-seam record naming `P4.6` and `P7.17`. Acceptance: `TASK-PLAT4-DEFECT-004` is raised to the plan owner as a missing dependency row.
7. [*] Raise `TASK-PLAT4-OBS-002` to the Architecture Owner and the `FEAT-PLAT-002` owner and record which PostgreSQL major version is authoritative. Deliverable: version decision record. Acceptance: this task list consumes that decision and pins no version of its own.

---

# Phase 1 – Discovery and Analysis

1. [*] Transcribe the plan's `FEAT-PLAT-004` scope items (a)–(f) into a coverage table mapping each to the tasks that deliver it. Deliverable: scope-coverage table. Acceptance: no scope item is unmapped and no task is unmapped to a scope item.
2. [*] Establish the ownership split of `ARC-REL-001…008`, which the feature card cites as a block. Deliverable: ownership table. Acceptance: records that this feature owns `ARC-REL-006` (broker outage becomes a monitored backlog) and the outbox limb of `ARC-REL-004` (bounded jittered retry), while `-001`, `-002`, `-003`, `-005`, `-007` and `-008` belong to `FEAT-OPS-003`, `FEAT-IAM-001`, `FEAT-PLAT-006`, `FEAT-PLAT-003`, `FEAT-DLV-001` and `FEAT-PLAT-006` respectively.
3. [*] Transcribe the §14.6 idempotency inventory into a machine-readable manifest with one row per operation: key, store, owning feature, and the proving test once it exists. Deliverable: `contracts/idempotency-inventory.yaml`. Acceptance: all thirteen §14.6 rows present; only the outbox-publication row is implementable by this feature; every other row names its owner.
4. [*] Analyse the §11.4 queue topology and separate what this feature declares from what its consumers declare. Deliverable: topology ownership note. Acceptance: `integration.<context>` and `integration.dlq` are this feature's; `grading.*` and `notification.*` belong to `FEAT-GRD-002/003/004` and `FEAT-NOTF-001`.
5. [*] Analyse the plan §14.4 verification attribution against §19.8 and record the correction. Deliverable: verification-ownership note carrying `TASK-PLAT4-DEFECT-005`. Acceptance: names `ARC-VERIFY-006` (integration limb) as this feature's, states that the static limb is `FEAT-PLAT-001 tasks.md` `P4.24`'s, and states plainly that event compatibility and the §14.6 inventory have no register identifier.
6. [*] Inventory the events the eight downstream features are known to need, from their plan cards, without designing them. Deliverable: prospective event register. Acceptance: used only to size the contract mechanism; each row marked as owned by its feature.
7. [*] Document the failure modes the outbox must survive, with the observable consequence of each: crash before commit, crash after commit before publish, crash mid-batch, broker unavailable, broker accepts then relay crashes before the state update, consumer crashes after effect before ack, duplicate delivery, out-of-order delivery, poison payload. Deliverable: failure-mode table — the specification the Phase 7 suite is generated from.
8. [*] Confirm the `cbt-worker` connection arithmetic still holds: pool 10 = 4 grading + 6 for relay, sweepers and dispatcher (§17.1). Deliverable: connection-budget note. Acceptance: the relay's steady-state connection use is stated and fits within the 6, with the CI stage 4a envelope check unaffected.

---

# Phase 2 – Architecture and Design

1. [*] Design the `outbox.outbox_event` table: identity, `tenant_id`, `aggregate_id`, `event_type` (`<context>.<Event>.v<n>`), payload, `correlation_id`, W3C trace context, `state`, `attempt_count`, `claim_expires_at`, `claimed_by`, `created_at`, `published_at`, `last_error`. Deliverable: table design note. Acceptance: every column is justified by a named requirement or §11.2 property; payload carries no credential material by construction of the payload rule (`P2.9`).
2. [*] Design the row state machine `PENDING → CLAIMED → PUBLISHED`, with `CLAIMED → PENDING` on claim expiry and `CLAIMED → FAILED` at `attempt_count` 8, and no transition out of `PUBLISHED` or `FAILED` except by an audited operator redrive. Deliverable: state-machine diagram and transition table. Acceptance: every transition names its guard; `FAILED` is terminal for the relay.
3. [*] Design the `app_outbox_relay` role: `SELECT, UPDATE` on `outbox.outbox_event` only, no `INSERT`, no `DELETE`, no grant on any other schema, membership granted to `app_worker` only. Deliverable: role design note carrying `TASK-PLAT4-DEFECT-001`. Acceptance: states explicitly that `app_api` and `app_pindist` never receive membership, and that the role cannot write a business table.
4. [*] Design the two RLS policies on `outbox.outbox_event`: a tenant-predicate policy for module roles on the write path, and a role-scoped policy admitting `app_outbox_relay` to all rows on the drain path, with `FORCE ROW LEVEL SECURITY` retained. Deliverable: RLS policy design carrying `TASK-PLAT4-DEFECT-003`. Acceptance: the relay's first statements are `SET LOCAL ROLE app_outbox_relay` plus a sentinel context so rule R9 and `current_setting(…, false)` still hold; a module role can never read another tenant's row.
5. [*] Design the relay claim query verbatim to §11.2: `UPDATE … SET state='CLAIMED', claim_expires_at = now() + interval '30 seconds' WHERE state='PENDING' … ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT :batch RETURNING *`. Deliverable: claim-query design. Acceptance: oldest-first; two concurrent relays claim disjoint sets; no relay waits on another's lock.
6. [*] Design the publish step: publisher confirms, per-row state update on ack, `attempt_count` increment with bounded jittered backoff on nack or timeout (`ARC-REL-004`), and **sequential** publication within a batch. Deliverable: publish algorithm. Acceptance: a broker that never acks cannot advance a row to `PUBLISHED`; oldest-first drain survives partial batch failure.
7. [*] Design the delivery contract this platform actually offers and write it down as the normative statement every consumer is built against: at-least-once, no ordering guarantee, idempotent apply on the business key, version-guarded. Deliverable: `contracts/events/DELIVERY-CONTRACT.md` carrying `TASK-PLAT4-DEFECT-006`. Acceptance: names the consistent-hash exchange as an available, unused seam and the conditions under which it would be adopted.
8. [*] Design the consumer idempotency primitive: a `processed_event` table per consuming module schema, unique on `outbox_event_id`, inserted in the same transaction as the effect, with the consumer's own §14.6 business key as the second guard. Deliverable: consumer-dedupe design. Acceptance: explains why the table cannot live in `outbox` (module roles hold no `SELECT` there) and why a memory-only guard is not one.
9. [*] Design the event payload rule: no PIN, OTP, token, key or credential material; no personal data beyond what the named consumer needs; identifiers preferred over values. Deliverable: payload minimisation rule. Acceptance: expressed as a checkable rule over the JSON Schemas, not as guidance.
10. [*] Design the JSON Schema baseline and the CI stage 9 compatibility matrix: `contracts/events/<context>.<Event>.v<n>.json` as the registered baseline; add-optional-field and add-event-type pass; remove, rename, narrow or re-semantic fail; a new enum value fails unless a consumer default is documented; a version is retired only after 30 days of zero consumption. Deliverable: compatibility specification. Acceptance: each §11.3 row maps to one checker assertion.
11. [*] Design retention: monthly-or-weekly range partitions on `created_at`, published rows detached after 7 days, and the narrow `app_outbox_maintenance` role that performs attach and detach. Deliverable: retention design carrying `TASK-PLAT4-DEFECT-002`. Acceptance: states that the audit record, not the outbox, is the evidence (§11.2); detach is age-driven, never release-driven; `FAILED` rows are never detached while unresolved.
12. [*] Design the `integration.<context>` and `integration.dlq` topology: quorum queues, prefetch 32, dead-letter routing, and a distinct dead-letter reason for an unhandled event version. Deliverable: topology design. Acceptance: an unhandled version is distinguishable from a poison payload in both the DLQ and the alert.
13. [*] Design the runtime configuration surface — batch size and tick interval from `platform.platform_config` — with bounds and a load-time rejection of an out-of-range value. Deliverable: configuration design. Acceptance: satisfies `ARC-RISK-014`'s "tunable without deploy" while preserving `ARC-VERIFY-018`.
14. [*] Design the two conformance rules: asynchronous cross-module propagation is `OutboxWriter`-only except under an `ADR-023` composite role, and the answer-acceptance path writes no outbox row. Deliverable: conformance-rule specification. Acceptance: both are expressible as ArchUnit rules plus one `pg_roles` assertion; scope items (f) and `ARC-PLAT-006` are covered.
15. [*] Design the operator redrive of a `FAILED` row and of a dead-lettered message as an audited action with an actor, a reason and an audit event. Deliverable: redrive design. Acceptance: a redrive re-checks the consumer's idempotency guard, so a drain after a partial success cannot duplicate an effect.

---

# Phase 3 – Data and Infrastructure

1. [ ] Author the migration creating `outbox.outbox_event` as a range-partitioned table on `created_at`, owned by `app_migrator`, with the columns from `P2.1` and a `CHECK` constraint enumerating the four states. Deliverable: Flyway migration. Depends on `P0.2`. Acceptance: created by `app_migrator`, so the `ALTER DEFAULT PRIVILEGES` bootstrap grants `INSERT` to all twelve module roles with no further statement.
2. [ ] Author the index set: the relay claim index on `(state, created_at)` partial to `PENDING`, the reclamation index on `(state, claim_expires_at)` partial to `CLAIMED`, and `(tenant_id, aggregate_id, created_at)` for operator lookup. Deliverable: index migration. Acceptance: created `CONCURRENTLY` where the table is non-empty, per `FEAT-PLAT-005`'s rule; the claim query plans as an index scan under `EXPLAIN`.
3. [ ] Author the RLS migration per `P2.4`: enable RLS, `FORCE ROW LEVEL SECURITY`, the tenant-predicate policy for module roles and the role-scoped policy for `app_outbox_relay`. Deliverable: RLS migration. Acceptance: reuses `FEAT-PLAT-002 tasks.md` `P4.14`'s RLS DDL template rather than hand-rolling the policy, and `P4.12`'s blocking `pg_class` catalogue gate passes for this table without an exemption.
4. [ ] Author the migration creating `app_outbox_relay` with `SELECT, UPDATE` on `outbox.outbox_event` only, and `GRANT app_outbox_relay TO app_worker`. Deliverable: role migration. Depends on `P0.5`. Acceptance: `app_api` and `app_pindist` are not members; the role holds no grant in any other schema, asserted by `P7.10`.
5. [ ] Author the migration creating `app_outbox_maintenance` with partition attach and detach privilege scoped to `outbox.outbox_event`, and no privilege on row data beyond what detach requires. Deliverable: maintenance-role migration. Depends on `P0.5`. Acceptance: cannot read a payload; cannot touch any other schema.
6. [ ] Author the partition-provisioning and detach routine per `P2.11`, driven by the background role on a schedule. Deliverable: retention routine plus its migration. Acceptance: a future partition always exists before it is needed; a partition containing an unresolved `FAILED` row is not detached.
7. [ ] Declare the RabbitMQ topology from `P2.12` as code: the `integration` exchange, `integration.<context>` quorum queues, `integration.dlq`, and the dead-letter bindings. Deliverable: topology declaration executed at startup on the background role only. Acceptance: idempotent — a second startup redeclares without error; the request role declares nothing.
8. [ ] Add the RabbitMQ Testcontainer to the CI stage 8 harness alongside the existing PostgreSQL container, reusing one instance across the suite where isolation permits. Deliverable: test harness. Acceptance: integration tests run against a real broker with real quorum queues, per §18.1 stage 8.
9. [ ] Create `contracts/events/` with the schema layout, the `DELIVERY-CONTRACT.md` from `P2.7`, and one reference schema used only by tests. Deliverable: contract directory. Acceptance: the directory is the registered baseline the CI stage 9 checker diffs against.
10. [ ] Author the `platform.platform_config` rows for relay batch size and tick interval with their bounds. Deliverable: configuration migration. Acceptance: defaults are §11.2's 200 and 200 ms; an out-of-range value is rejected at load by `P4.7`.

---

# Phase 4 – Backend Implementation

1. [ ] Implement the `OutboxWriter` adapter in `platform.infra` against `FEAT-PLAT-003`'s port, inserting one row inside the caller's transaction. Deliverable: adapter. Depends on `P0.3`, `P3.1`. Acceptance: exposes no connection- or template-supplying overload, so an out-of-transaction write is inexpressible; a rolled-back transaction leaves no row.
2. [ ] Implement event-type resolution and validation: `<context>.<Event>.v<n>` parsed and rejected at construction if malformed, with the payload validated against its registered schema before insert. Deliverable: event-type value object plus validator. Acceptance: an unregistered type or a payload that fails its schema is rejected at the writer, not at the relay — a bad event never commits.
3. [ ] Implement correlation and trace carriage: the writer reads the ambient correlation identifier and trace context from `FEAT-PLAT-003`'s propagation and writes them to the row. Deliverable: carriage implementation. Acceptance: a write with no correlation identifier in context fails rather than writing a null — `NFR-OBS-002` depends on the identifier existing on every row.
4. [ ] Implement the relay claim step per `P2.5`. Deliverable: claim component. Acceptance: batch-bounded, oldest-first, `SKIP LOCKED`; proved disjoint under concurrency by `P7.5`.
5. [ ] Implement the relay publish step per `P2.6`: publisher confirms, per-row state update, `attempt_count` increment with bounded jittered backoff, and the transition to `FAILED` at 8. Deliverable: publish component. Acceptance: a nack never advances a row to `PUBLISHED`; a `FAILED` row raises the operator alert from `P9.3` and does not block the head.
6. [ ] Implement the relay singleton using `pg_advisory_xact_lock` keyed on the sweep name, with a `TODO`-free, explicitly documented adoption seam for `FEAT-PLAT-006`'s registry. Deliverable: singleton wrapper carrying `TASK-PLAT4-DEFECT-004`. Acceptance: N replicas produce exactly one active relay per tick, proved by `P7.6`; the seam names the task that closes it (`P7.17`).
7. [ ] Implement bounded runtime configuration loading per `P2.13`. Deliverable: configuration component. Acceptance: an out-of-range value fails the load with a named error and the previous valid value stays in force; the change requires no deploy.
8. [ ] Implement stale-claim reclamation: a `CLAIMED` row past `claim_expires_at` returns to `PENDING` with the reclamation counted. Deliverable: reclamation sweep. Acceptance: a relay killed mid-batch leaves no permanently stranded row, proved by `P7.7`.
9. [ ] Implement the relay's security-context installation: `SET LOCAL ROLE app_outbox_relay` and the sentinel tenant context as the transaction's first statements, with reset on release including cancellation and timeout paths. Deliverable: relay context decorator, reusing `FEAT-PLAT-002`'s `SecurityContextInitializer` seam rather than a second mechanism. Acceptance: rule R9 holds; `ARC-DATA-026` is not weakened.
10. [ ] Implement the consumer-side `processed_event` primitive from `P2.8` as a reusable `platform.infra` component plus its per-schema migration template. Deliverable: dedupe component and template. Acceptance: a redelivered event finds the row and returns without re-applying; the check and the effect share one transaction.
11. [ ] Implement version-aware consumer dispatch: a consumer declares the versions it handles; an unhandled version is dead-lettered with the distinct reason from `P2.12`. Deliverable: dispatch component. Acceptance: no code path silently acks an unhandled version; the out-of-order test `P7.8` passes against the same dispatcher.
12. [ ] Implement one reference consumer and an in-memory `OutboxWriter` test double, both test-scope only. Deliverable: reference consumer and double. Acceptance: no production wiring; every later feature's slice tests use the double instead of a broker.
13. [ ] Implement the JSON Schema generator that derives a schema from an event record type and fails the build if a committed baseline schema and the code disagree. Deliverable: generator task. Acceptance: a field added in code without a schema update fails; drift between code and baseline is impossible to merge.
14. [ ] Implement the CI stage 9 compatibility checker over `contracts/events/` per `P2.10`. Deliverable: Gradle task wired BLOCKING. Acceptance: every §11.3 row is asserted; proved by the deliberate breaking change in `P7.14`.
15. [ ] Implement the conformance rule that asynchronous cross-module propagation goes through `OutboxWriter` only, with the `ADR-023` composite-role list as the sole exception. Deliverable: ArchUnit rule plus the `pg_roles` assertion that the enumerated-flow table matches the roles actually granted. Acceptance: this is the integration limb of `ARC-VERIFY-006`; the static limb remains `FEAT-PLAT-001 tasks.md` `P4.24`'s.
16. [ ] Implement the conformance rule that the answer-acceptance path writes no outbox row. Deliverable: ArchUnit rule. Acceptance: cites `ADR-009` and `ARC-PLAT-006` in its failure message so a future author understands why, rather than deleting the rule.
17. [ ] Implement the §14.6 manifest coverage gate: every inventory row must name an owning feature, and once that feature has shipped, a proving test. Deliverable: manifest checker. Acceptance: a new duplicate-prone operation cannot be added without an inventory row; a row cannot lose its owner.
18. [ ] Implement the operator redrive path from `P2.15` for a `FAILED` row and for a dead-lettered message. Deliverable: redrive component, exposed through `FEAT-OPS-002`'s surface rather than an HTTP route of its own. Acceptance: emits an audit event with actor and reason; re-checks the consumer guard before re-effecting.
19. [ ] Instrument the relay, the writer and the consumer primitive with the metrics designed in `P9.1`. Deliverable: instrumented components. Acceptance: no metric carries `tenant_id` as an unbounded label.

---

# Phase 5 – Frontend Implementation

**None.** This feature adds no user-facing surface. Named so a reviewer can distinguish absence from
oversight.

1. [ ] Record that operator visibility of backlog, `FAILED` rows and dead-letter contents is delivered through `FEAT-OPS-002` and `FEAT-GRD-005`, and that this feature supplies only the queries and the redrive command those surfaces call. Deliverable: interface note handed to both owners. Acceptance: no HTTP route and no OpenAPI change originates here, so the CI stage 9 breaking-change diff has nothing to assess for this feature.

---

# Phase 6 – Security and Hardening

1. [ ] Source broker credentials from the external secret manager via workload identity at startup, never from an image, source file or config map. Deliverable: credential wiring (`REQ-SEC-008`, `ARC-SEC-013`). Acceptance: startup fails if the secret is unavailable; the credential is held in memory only.
2. [ ] Enable TLS to the broker and confirm certificate verification is on in every environment. Deliverable: transport configuration. Acceptance: a self-signed or mismatched certificate fails the connection rather than warning.
3. [ ] Give the background role its own broker user scoped to the `integration.*` topology, with no configure or delete privilege on other vhost objects. Deliverable: broker permission set. Acceptance: the relay cannot delete a queue it does not own; the request role has no broker credential at all.
4. [ ] Implement the payload minimisation rule from `P2.9` as an automated check over the registered schemas: a property whose name matches the kernel `SecretFieldPattern` (`FEAT-PLAT-003 tasks.md` `P4.14`) or an unapproved personal-data field fails the build. Deliverable: payload check wired into CI stage 9. Acceptance: reuses the kernel pattern rather than defining a fourth copy of it.
5. [ ] Extend the CI stage 10 secret-leak scan to event payloads captured from the integration suite. Deliverable: scan extension carrying `TASK-PLAT4-OBS-003`. Acceptance: a PIN or OTP planted in a test payload fails the build; raised to `FEAT-SEC-001` as an owned extension.
6. [ ] Review `app_outbox_relay` and `app_outbox_maintenance` against least privilege with Security. Deliverable: signed least-privilege review. Acceptance: neither role can read or write any business table; neither can `DELETE` an outbox row; `app_outbox_maintenance` cannot read a payload.
7. [ ] Confirm the relay cannot be reached from the request or isolated roles: no relay bean, no broker credential and no `app_outbox_relay` membership on `app_api` or `app_pindist`. Deliverable: role-isolation review. Acceptance: asserted by test in `P7.11`, not by configuration reading.
8. [ ] Confirm the relay's `last_error` column and its log lines carry no payload content and no provider or driver text that could contain a credential. Deliverable: error-hygiene review. Acceptance: `last_error` is a classified reason plus a correlation identifier, consistent with `ARC-SEC-010`.
9. [ ] Confirm no credential, broker URI or secret exists in source, migrations or test fixtures. Deliverable: scan result from the history-aware secret scanner (CI stage 11).

---

# Phase 7 – Testing and Quality Assurance

1. [ ] Prove write-in-transaction atomicity against real PostgreSQL: a committed business transaction leaves exactly one outbox row; a rolled-back one leaves none. Deliverable: integration test. Acceptance: the plan's first acceptance outcome — an event exists if and only if its business transaction committed.
2. [ ] Prove the negative direction with injected faults at each point of the failure-mode table from `P1.7`. Deliverable: fault-injection suite. Acceptance: no phantom event is ever published for a rolled-back change; a crash after commit before publish leaves a `PENDING` row the relay finds.
3. [ ] Prove redelivery idempotency end to end: the same event delivered twice produces exactly one effect, asserted through the `processed_event` guard and the reference consumer's business key. Deliverable: integration test. Acceptance: the plan's third acceptance outcome.
4. [ ] Prove the relay publishes exactly the committed set after a broker outage: stop RabbitMQ for 10 minutes under load, confirm the request path is unaffected, then confirm the backlog drains oldest-first on recovery with no loss and no duplicate effect. Deliverable: integration test mirroring the §19.7 drill. Acceptance: `ARC-REL-006`, `NFR-REL-002`; the drill programme itself remains `FEAT-OPS-006`'s.
5. [ ] Prove `FOR UPDATE SKIP LOCKED` batch semantics under concurrency: N parallel relays claim disjoint, non-overlapping sets and none blocks on another. Deliverable: concurrency test. Acceptance: plan §14.2 names this behaviour as requiring a concurrent-access test for this feature.
6. [ ] Prove the singleton: N replicas contending for the relay lock produce exactly one active relay per tick, and a lock lost mid-tick stops that relay rather than letting two publish. Deliverable: integration test. Acceptance: covers `P4.6` ahead of `FEAT-PLAT-006`'s adoption.
7. [ ] Prove stale-claim reclamation: kill a relay mid-batch and confirm every `CLAIMED` row returns to `PENDING` after expiry and is published exactly once. Deliverable: integration test. Acceptance: no row is stranded and none is published twice.
8. [ ] Prove consumers are order-insensitive: deliver a causally later event before its predecessor and confirm the consumer's version guard produces the correct final state. Deliverable: out-of-order delivery test. Acceptance: discharges the resolution adopted for `TASK-PLAT4-DEFECT-006`.
9. [ ] Prove tenant isolation on the outbox: a module role sees only its own tenant's rows, a query without an installed tenant context fails, and `app_outbox_relay` sees all rows. Deliverable: RLS integration test. Acceptance: discharges `TASK-PLAT4-DEFECT-003`; extends `ARC-VERIFY-005`'s zero-rows behaviour to this table.
10. [ ] Prove the relay role's least privilege by test: it cannot `INSERT` or `DELETE` an outbox row, and cannot touch any table in any module schema. Deliverable: negative privilege test. Acceptance: four explicit denials asserted.
11. [ ] Prove role isolation: the request role exposes no relay bean, holds no broker credential and cannot assume `app_outbox_relay`. Deliverable: role-isolation test. Acceptance: covers `P6.7`.
12. [ ] Prove the poison path: a payload that fails publication eight times becomes `FAILED`, alerts, and does not block subsequent rows. Deliverable: integration test. Acceptance: head-of-line blocking is demonstrated absent, not assumed.
13. [ ] Prove the unhandled-version path: an event version no consumer declares is dead-lettered with the distinct reason and its own alert, and is never acked as handled. Deliverable: integration test. Acceptance: §11.3's "never silently dropped".
14. [ ] Prove the compatibility gate by deliberately introducing a breaking change — remove a field from a registered schema without a version bump — and confirming CI stage 9 fails. Deliverable: retained negative build evidence. Acceptance: the plan's fourth acceptance outcome; the gate is demonstrated BLOCKING, not merely configured.
15. [ ] Prove the compatible-change path: adding an optional field passes; adding an enum value fails without a documented consumer default. Deliverable: compatibility test matrix. Acceptance: every §11.3 row has a passing and a failing case.
16. [ ] Prove the two conformance rules by writing a violating fixture for each — a direct cross-module write outside a composite role, and an outbox insert on the answer-acceptance path — and confirming CI stage 4 fails. Deliverable: two negative conformance tests. Acceptance: the integration limb of `ARC-VERIFY-006` is green and retained.
17. [ ] Close the `FEAT-PLAT-006` adoption seam: replace the local advisory-lock acquisition with the registry, re-run `P7.6` and confirm `FEAT-PLAT-006`'s own exactly-one-sweep test still passes. Deliverable: adoption evidence. Depends on `FEAT-PLAT-006` delivery. Acceptance: one lock mechanism remains in the codebase, not two.
18. [ ] Prove the retention path: a partition older than 7 days containing only `PUBLISHED` rows is detached, and one containing an unresolved `FAILED` row is not. Deliverable: retention test. Acceptance: covers `P3.6`; asserts the audit record survives independently.
19. [ ] Prove bounded configuration: an out-of-range batch size is rejected at load with the previous value retained, and a valid change takes effect without a restart. Deliverable: configuration test. Acceptance: `ARC-VERIFY-018` is not weakened by the tunability.
20. [ ] Measure the writer's cost on a propagating operation and confirm the extra insert is within the connection and latency budget from `P1.8`. Deliverable: measurement record. Acceptance: `ADR-009`'s "one extra insert per propagating operation" is quantified rather than assumed; the answer path is confirmed to carry none.
21. [ ] Add slice-level tests for the reference consumer using the in-memory double, so the pattern later features copy is demonstrated at both levels. Deliverable: slice tests. Acceptance: CI stage 7's "a slice with no test fails the build" is satisfied for everything this feature adds.
22. [ ] Verify all five plan acceptance outcomes explicitly, one assertion each, and record which task discharges each. Deliverable: acceptance-outcome verification record.

---

# Phase 8 – Deployment and Release

1. [ ] Wire CI stage 8 to run the outbox integration suite against real PostgreSQL and real RabbitMQ, BLOCKING. Deliverable: pipeline change. Acceptance: §18.1 stage 8's "real outbox" is now literally true.
2. [ ] Wire CI stage 9 to run the event-schema compatibility checker and the payload minimisation check, BLOCKING. Deliverable: pipeline change. Acceptance: an unversioned breaking change or a credential-shaped payload field fails the build.
3. [ ] Wire the two conformance rules into CI stage 4 alongside the existing R1–R8 set. Deliverable: pipeline change. Acceptance: the rules run on every commit, not on a schedule.
4. [ ] Register the relay's connection use against the CI stage 4a connection envelope and confirm `max_connections` arithmetic is unchanged. Deliverable: envelope evidence with all four figures published. Acceptance: `ARC-PERF-006`; the `cbt-worker` pool of 10 still covers 4 grading plus the relay, sweepers and dispatcher.
5. [ ] Confirm the relay and its topology declaration are enabled on the background role only, by profile. Deliverable: profile configuration. Acceptance: the request and isolated roles start with no relay bean and no broker connection.
6. [ ] Provision RabbitMQ for staging with quorum-queue policies and the per-role user from `P6.3`, with Platform Ops. Deliverable: broker provisioning record. Acceptance: quorum replication factor stated; the `integration.*` policy applied.
7. [ ] State the rollback path: the migrations are additive (new table, new roles, new config rows), so a rollback of application code leaves them harmless and the relay simply stops draining. Deliverable: rollback note. Acceptance: names the one irreversible act — a detached partition — and states that detach is disabled until `P7.18` is green in staging.
8. [ ] Record the deployment ordering constraint: the migration Job creates the roles and table before any background pod starts, per `FEAT-PLAT-005`'s entrypoint discipline. Deliverable: ordering note. Acceptance: a background pod starting before the role exists fails fast rather than logging and idling.
9. [ ] Re-run the `FEAT-PLAT-002 tasks.md` and `FEAT-PLAT-003 tasks.md` suites after this feature's migrations land, to confirm the added roles and RLS policies have not disturbed the isolation or kernel guarantees. Deliverable: sibling-suite evidence. Acceptance: `ARC-VERIFY-024` re-run and retained.

---

# Phase 9 – Monitoring and Operations

1. [ ] Define and register the metric set §16.2 omits entirely: `outbox_backlog_depth` by state, `outbox_oldest_pending_age_seconds`, `outbox_relay_published_total`, `outbox_relay_publish_failure_total` by reason, `outbox_relay_tick_total` and its duration, `outbox_stale_claim_reclaimed_total`, and `outbox_event_failed_total`. Deliverable: metric definitions carrying `TASK-PLAT4-OBS-001`. Acceptance: labels are bounded; no metric carries `tenant_id`.
2. [ ] Wire the §16.4 backlog alert as specified — `PENDING` > 5,000 or oldest > 5 minutes, P2 — against `P9.1`'s metrics. Deliverable: alert rule. Acceptance: the only outbox alert the architecture defines is live and demonstrably fires in staging.
3. [ ] Propose the four alerts §16.4 lacks: any `FAILED` row (P2), relay tick liveness gap beyond 60 s (P2), `integration.dlq` depth ≥ 1 (P2), and unhandled-version dead-letter ≥ 1 (P2, distinct from poison). Deliverable: alert proposals raised to `FEAT-OPS-004` under `TASK-PLAT4-OBS-001`. Acceptance: raised as a gap, not invented into §16.4.
4. [ ] Request the dashboard panels this feature needs on dashboard 6 (Platform health): backlog by state, oldest pending age, relay throughput, DLQ depths. Deliverable: panel request to `FEAT-OPS-004`. Acceptance: §16.5 defines no outbox panel today; the request records that.
5. [ ] Confirm the outbox relay span is emitted as §16.3 requires ("outbox relay batch") and that trace context flows row → message → consumer. Deliverable: trace verification. Acceptance: the end-to-end join test remains `FEAT-OBS-001`'s; this task proves the carrier exists for it.
6. [ ] Write the backlog runbook: how to distinguish a broker outage from a stalled relay from a genuine volume spike, and what to tune. Deliverable: `docs/runbooks/outbox-backlog.md`.
7. [ ] Write the `FAILED`-row runbook: how to inspect, classify and redrive, and why redrive is audited. Deliverable: `docs/runbooks/outbox-failed-rows.md`. Acceptance: states that a redrive re-checks the consumer guard.
8. [ ] Write the dead-letter drain runbook for `integration.dlq`, including the unhandled-version case, which is resolved by deploying a consumer rather than by redriving. Deliverable: `docs/runbooks/integration-dlq.md`.
9. [ ] Write the stalled-relay runbook: advisory-lock holder identification, safe restart, and the reclamation window to expect. Deliverable: `docs/runbooks/outbox-relay-stalled.md`.
10. [ ] Add the outbox checks to the operational readiness review for Phase 6: backlog at zero, no `FAILED` rows, DLQs empty, relay ticking. Deliverable: readiness checklist entry handed to `FEAT-OPS-001`.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the event-authoring guide: naming, versioning, the payload minimisation rule, how to register a schema and what the CI stage 9 checker will reject. Deliverable: `docs/event-authoring.md` — the guide every later publishing feature follows.
2. [ ] Publish the delivery contract from `P2.7` as the normative statement consumers are built against. Deliverable: `contracts/events/DELIVERY-CONTRACT.md`. Acceptance: states at-least-once, no ordering guarantee, idempotent apply, version-guarded — in those words.
3. [ ] Publish the consumer-authoring guide: the `processed_event` guard, the §14.6 business key, version declaration, and why an in-memory guard is not idempotency. Deliverable: `docs/consumer-authoring.md`.
4. [ ] Publish the §14.6 idempotency inventory manifest from `P1.3` as the normative operation → key → store → owner map. Deliverable: `contracts/idempotency-inventory.yaml` plus `docs/idempotency-inventory.md`. Acceptance: the coverage gate from `P4.17` is documented as the mechanism that keeps it current.
5. [ ] Publish the outbox operations guide: the state machine, the relay algorithm, the tunables and their bounds, and the retention behaviour. Deliverable: `docs/outbox-operations.md`.
6. [ ] Publish the `ADR-023` boundary as a rule slice authors can apply: when a write may be synchronous and cross-module, and why the list is closed. Deliverable: `docs/async-propagation-rule.md`. Acceptance: discharges scope item (f) as documentation as well as as a conformance rule.
7. [ ] Publish the two new database roles and their grants as an addendum to the §9.2 grant matrix, cross-referenced from `FEAT-PLAT-002 tasks.md`'s matrix artifact. Deliverable: grant-matrix addendum. Acceptance: a reader of the matrix finds the relay roles without reading this task list.
8. [ ] Publish the adoption-seam record for `FEAT-PLAT-006` so a reader of the four Phase 0 task lists can see where the local advisory lock ended. Deliverable: documented seam closure.
9. [ ] Publish the consistent-hash exchange seam: what it would cost, when it would be adopted and what would have to become true first. Deliverable: seam note. Acceptance: the unused option is recorded so a future ordering requirement is a decision rather than a rediscovery.
10. [ ] Raise `TASK-PLAT4-DEFECT-001` through `-006` and `TASK-PLAT4-OBS-001` through `-003` to the Architecture Owner as baseline defects for the next revision, each with the resolution adopted here; raise `-001` and `-002` additionally to the `FEAT-PLAT-002` owner, `-004` and `-005` to the plan owner, `-006` to the Architecture Owner as a §11.2 wording correction, and `-002` (OBS) to both. Deliverable: nine defect records with acknowledgements.
11. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, verification identifiers and retained artifacts. Deliverable: updated matrix rows.
12. [ ] Run a walkthrough with the engineering team covering the write-in-transaction rule, the delivery contract, the consumer guard, the version-bump rule and the two conformance rules. Deliverable: session record plus attendance. Acceptance: every engineer who will publish an event has attended before Phase 1 begins.

---

# Appendix A – Traceability

| Requirement / decision                                                | Architecture reference | Tasks                                              | Verification                                                    |
|-----------------------------------------------------------------------|------------------------|----------------------------------------------------|-----------------------------------------------------------------|
| `NFR-REL-001` no accepted write lost                                   | §11.2, §14.1           | `P3.1`, `P4.1`, `P4.5`, `P4.8`                     | `P7.1`, `P7.2`, `P7.4`, `P7.7`                                  |
| `NFR-REL-002` broker outage cannot affect exam delivery                | §11.2, §14.4           | `P2.6`, `P3.7`, `P4.5`                             | `P7.4`, `P7.12` — head-of-line blocking demonstrated absent      |
| `NFR-REL-003` duplicate-prone operations are idempotent                | §11.2, §14.6           | `P1.3`, `P2.8`, `P4.10`, `P4.17`                   | `P7.3`, `P7.8`; manifest gate `P4.17`                            |
| `NFR-MAINT-001` externally visible change is versioned                 | §11.3                  | `P2.10`, `P4.2`, `P4.13`, `P4.14`                  | CI 9 BLOCK; proved by `P7.14`, `P7.15`                           |
| `REQ-RSLT-038` grading reprocess publishes no second result            | §11.2, §14.6           | `P1.3`, `P2.8`, `P4.10`                            | `P7.3`; the grading-lane limb is `FEAT-GRD-002`'s                |
| `DEP-002` cond. 2/9 no second PIN or OTP from a redelivery             | §11.4, §14.6           | `P1.3`, `P2.9`, `P4.10`                            | `P7.3`, `P6.4`, `P6.5`; provider semantics are `FEAT-NOTF-002`'s |
| `CONSTRAINT-PLAT-002` PostgreSQL is the sole authoritative store       | §11.2, §12.1           | `P3.1`, `P3.3`                                     | The outbox is a table, not a broker feature                      |
| `CONSTRAINT-PLAT-004` event-driven integration via outbox              | §11.2, §9.2            | `P4.1`, `P4.15`                                    | `ARC-VERIFY-006` integration limb, `P7.16`                       |
| `REQ-SEC-008` broker credentials from the secret manager               | §17.5, `ARC-SEC-013`   | `P6.1`, `P6.2`, `P6.3`                             | `P6.9` history-aware secret scan, CI 11                          |
| `ADR-009` outbox for all asynchronous propagation                      | §11.2, §23.7           | `P2.1`–`P2.6`, `P4.1`–`P4.9`                       | `P7.1`–`P7.7`                                                    |
| `ADR-023` synchronous collaboration is the enumerated exception        | §9.2 `ARC-DATA-027`    | `P2.14`, `P4.15`, `P10.6`                          | `pg_roles` assertion in `P4.15`; `ARC-VERIFY-023` is `FEAT-EXAM-007`'s |
| `ARC-PLAT-011` row written in the business transaction                 | §11.2                  | `P4.1`, `P4.3`                                     | `P7.1`, `P7.2`                                                   |
| `ARC-PLAT-012` event versioning and evolution rules                    | §11.3                  | `P2.10`, `P4.13`, `P4.14`                          | `P7.14`, `P7.15`                                                 |
| `ARC-PLAT-006` answer submission carries no outbox row                 | §11.2, `ADR-009`       | `P4.16`                                            | `P7.16`, `P7.20`                                                 |
| `ARC-REL-004` bounded jittered retry                                   | §14.4, §23.7           | `P2.6`, `P4.5`                                     | `P7.12`                                                          |
| `ARC-REL-006` broker outage is a monitored backlog                     | §14.4, §23.7           | `P4.5`, `P9.1`, `P9.2`                             | `P7.4`; §19.7 drill is `FEAT-OPS-006`'s                          |
| `ARC-DATA-018` / `ARC-DATA-026` transaction-scoped context             | §9.4                   | `P2.4`, `P4.9`                                     | `P7.9`, `P7.10`, `P8.9` (`ARC-VERIFY-024` re-run)                |
| `ARC-RISK-014` outbox backlog after a prolonged outage                 | §11.2, §20             | `P2.13`, `P4.7`, `P9.2`, `P9.6`                    | `P7.4`, `P7.19`                                                  |
| `ARC-VERIFY-006` async propagation is outbox-only (integration limb)   | §19.8                  | `P4.15`, `P7.16`                                   | CI 4 + integration; static limb `FEAT-PLAT-001 tasks.md` `P4.24`         |
| Event-schema compatibility — **no register identifier exists**          | §11.3, §18.1 stage 9   | `P1.5`, `P4.14`, `P8.2`                            | CI 9 BLOCK; register gap raised in `P10.10`                      |
| §14.6 idempotency inventory — **no register identifier exists**         | §14.6                  | `P1.3`, `P4.17`, `P10.4`                           | Manifest coverage gate; register gap raised in `P10.10`          |
| Tenant isolation of the outbox table                                   | §9.4, §12.3            | `P2.4`, `P3.3`                                     | `P7.9`; extends `ARC-VERIFY-005` to this table                   |
| Observability of the outbox                                            | §16.2, §16.3, §16.4    | `P4.19`, `P9.1`–`P9.5`                             | Metrics live; four alerts raised as `TASK-PLAT4-OBS-001`         |
| Correlation and trace carriage across the broker                       | §16.1, §16.3           | `P4.3`, `P9.5`                                     | Carrier proved here; end-to-end join is `FEAT-OBS-001`'s         |
| Retention — published rows pruned after 7 days                         | §11.2, §14.1           | `P2.11`, `P3.5`, `P3.6`                            | `P7.18`                                                          |
| `PLAN-BLOCKER-001`                                                     | plan §10, §18.3        | `P0.1`                                             | Discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7`                     |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence
from oversight.

| Excluded                                                                                                       | Owner                                              |
|----------------------------------------------------------------------------------------------------------------|----------------------------------------------------|
| The specific events each module publishes, and their schemas                                                    | Each owning feature, following `docs/event-authoring.md` |
| The `outbox` schema itself, module roles, grants, `ALTER DEFAULT PRIVILEGES`, the RLS convention and its gate    | `FEAT-PLAT-002`                                    |
| The `OutboxWriter` **port**, `TenantId`, `ActorContext`, the clock, the correlation identifier, `SecretFieldPattern` | `FEAT-PLAT-003`                                 |
| Expand/contract discipline, forbidden-operation rejection, `CONCURRENTLY` enforcement and CI stage 12            | `FEAT-PLAT-005`                                    |
| Runtime roles, the advisory-lock registry, scheduler singleton policy and graceful shutdown (`ARC-REL-008`)      | `FEAT-PLAT-006`                                    |
| The audit table, hash chain and in-transaction `AuditEmitter`                                                    | `FEAT-AUD-001`                                     |
| Structured-logging infrastructure, metric registration, tracing exporters and the end-to-end propagation test     | `FEAT-OBS-001`                                     |
| Dashboards, panel definitions and alert-rule authoring                                                            | `FEAT-OPS-004`                                     |
| The `grading.*` queues, priority lane, TTL retry queues and pre-warmed capacity                                  | `FEAT-GRD-002`, `FEAT-GRD-003`, `FEAT-GRD-004`     |
| The `notification.*` queues, dispatch record, templating and provider semantics (`ADR-025`, `ARC-VERIFY-015`)     | `FEAT-NOTF-001`, `FEAT-NOTF-002`                   |
| Webhook ingestion, replay protection and the `REQ-SEC-015` control set (`ARC-VERIFY-014`)                        | `FEAT-NOTF-003`                                    |
| The OpenAPI breaking-change diff (`ARC-VERIFY-012`) — this feature adds no route                                  | The owning contract feature                        |
| The `ProblemDetail` fault-injection suite (`ARC-VERIFY-013`)                                                      | `FEAT-PLAT-003`, extended by `FEAT-SEC-001`        |
| Exam-entry atomicity and the composite-role protocol (`ARC-VERIFY-023`)                                          | `FEAT-EXAM-007`                                    |
| The per-operation PostgreSQL unique indexes of the §14.6 inventory                                                | The owning feature per row                         |
| Operator UI for backlog, `FAILED` rows and DLQ contents                                                           | `FEAT-OPS-002`, `FEAT-GRD-005`                     |
| The resilience drill programme and the §19.7 broker-outage drill as a scheduled exercise (CI 18)                  | `FEAT-OPS-006`                                     |
| RabbitMQ cluster provisioning, sizing and HA as infrastructure                                                    | Platform Ops (`P8.6` records the handover)         |
| A consistent-hash exchange and per-partition consumers                                                            | Unadopted seam, documented in `P10.9`              |
| Ratification of the architecture baseline as a governance act                                                     | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] Atomicity integration tests green: an event exists if and only if its business transaction committed (`P7.1`, `P7.2`).
2. [ ] Redelivery-idempotency integration tests green: a redelivered event produces no duplicate effect (`P7.3`).
3. [ ] The event-schema compatibility gate is **BLOCKING** in CI stage 9 and demonstrated to fail on an unversioned breaking change (`P4.14`, `P7.14`, `P8.2`).
4. [ ] Outbox backlog metrics and the §16.4 alert are live (`P9.1`, `P9.2`).
5. [ ] A relay or broker outage delays propagation but loses nothing, demonstrated (`P7.4`).
6. [ ] Outbox backlog depth and oldest-event age are visible and alerted (`P9.1`, `P9.2`, `P9.4`).
7. [ ] `FOR UPDATE SKIP LOCKED` relay batch semantics proved under concurrency (`P7.5`) — plan §14.2.
8. [ ] The §14.6 idempotency inventory is covered: transcribed, owned per row, and gated (`P1.3`, `P4.17`, `P10.4`).
9. [ ] Event payloads carry no PIN, OTP or other credential material and no unnecessary personal data (`P2.9`, `P6.4`, `P6.5`).
10. [ ] Broker credentials come from the external secret manager (`P6.1`, `P6.9`).
11. [ ] Synchronous atomic collaboration is stated and enforced as the enumerated `ADR-023` exception, not an implicit alternative (`P4.15`, `P7.16`, `P10.6`).

### Universal (plan §8.0), as far as this feature can discharge it

12. [ ] All five mapped acceptance outcomes verified, one assertion each (`P7.22`).
13. [ ] Unit, slice, integration and contract tests pass (`P7.21`, CI 5/7/8/9).
14. [ ] CI stage 4 is green for the code this feature adds, including the two new conformance rules (`P7.16`, `P8.3`).
15. [ ] Tenant isolation is enforced on `outbox.outbox_event` and covered by test (`P3.3`, `P7.9`); the isolation *matrix* remains `FEAT-PLAT-002`'s and `FEAT-SEC-001`'s.
16. [ ] Error hygiene: `last_error` and relay logs leak no payload or driver text (`P6.8`).
17. [ ] Database changes are additive and expand/contract-compliant, and pass CI stage 12 (`P3.1`–`P3.6`, `P8.7`).
18. [ ] Required telemetry exists (`P4.19`, `P9.1`); the rollback path is stated (`P8.7`).
19. [ ] No credential or secret exists in source, migrations or fixtures (`P6.9`).
20. [ ] Peer or AI review complete; no unresolved Critical or High defect remains.
21. [ ] The `FEAT-PLAT-006` adoption seam is closed and both sibling suites are green afterwards (`P7.17`, `P8.9`).
22. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.11`).
23. [ ] **Not dischargeable by this feature, and recorded as such:** in-transaction audit emission (`FEAT-AUD-001` — this feature emits an audit event only for an operator redrive, `P4.18`); the OpenAPI breaking-change diff (`ARC-VERIFY-012`, CI 9 — no route is added, `P5.1`); the end-to-end correlation-propagation test (`FEAT-OBS-001` — the carrier is proved here, `P9.5`); the four missing alerts and the dashboard panel (`TASK-PLAT4-OBS-001`, `FEAT-OPS-004`); the §19.7 broker-outage drill as a scheduled CI 18 exercise (`FEAT-OPS-006` — the equivalent integration test is `P7.4`); the payload leak-scan extension as a release gate (`FEAT-SEC-001`, `TASK-PLAT4-OBS-003`); RabbitMQ cluster provisioning and HA (Platform Ops).
