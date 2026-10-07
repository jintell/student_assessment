# Task List — `FEAT-AUD-001` ★ Immutable Hash-Chained Audit Store and In-Transaction Emission

## Overview

|                       |                                                                                                                                                                                                                                    |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-AUD-001`), §8.0 (universal DoR/DoD), §10 Phase 0, §11.2 track (c), §14.4, §14.5                                                                                                                |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §9.2 (grant matrix), §9.5 (`ARC-AUD-001…007`), §9.7 (`ARC-DATA-030/031`), §9.8, §15.2, §15.5, §16.2, §16.4, §16.5 dashboard 7, §18.1 stages 4/6/8/10/12/17, §19.3, §19.8, §19.9, `ADR-011` |
| Delivery phase        | Phase 0 — store, emission, chain, seal and verifier. Conditions **A6** and **A7** are evidenced in Phase 6 against the full load profile (`TASK-AUD1-DEFECT-005`)                                                                    |
| Dependencies          | `FEAT-PLAT-002` (`audit` schema, `ALTER DEFAULT PRIVILEGES` `INSERT` bootstrap, RLS), the ratified architecture (§9.5 append-only trigger convention), `FEAT-PLAT-003` (`ActorContext`, `TenantId`, controlled clock, `SecretFieldPattern`), `FEAT-PLAT-001` (module boundaries, R8 rule) |
| Consumed by           | Every state-changing and privileged-read feature in the programme. Directly named: `FEAT-PRIV-001` (retention execution, `ARC-VERIFY-032`), `FEAT-CORR-004` (result-evidence retention), `FEAT-OPS-004` (dashboard 7), `FEAT-OPS-003` (post-restore re-walk), and every feature that declares audit events against this feature's catalogue convention |
| Generated on          | 2026-09-03                                                                                                                                                                                                                        |
| Methodology           | Clean architecture, with the chain as the test case for it. The `AuditEmitter` **port** and `AuditEvent` live in `shared.kernel` so any module's slice can emit without importing the `audit` module. The **chain algebra** — canonical serialisation, `record_hash`, shard assignment, epoch identity, root derivation — is a **pure function** in `audit.domain` with no SQL, no KMS and no framework, exactly as `ADR-013` treats scoring. Persistence, KMS signing and the verifier's data access are adapters in `audit.infra` |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                            |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                               |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                          |

**Objective.** Provide an append-only, per-tenant hash-chained audit store the application cannot update or
delete, with audit events emitted inside the business transaction they describe, and with a chain design that
neither forks under concurrency nor breaks when expired evidence is disposed.

**Why the chain algebra is pure, and why that is not a stylistic preference.** `record_hash` is byte-exact and
permanent: every historical hash must remain reproducible for the lifetime of the longest retention class,
five years. A hash computed inside a repository, over whatever a JSON library happened to emit that release,
cannot be re-derived years later. Isolating the algebra in `audit.domain` makes it unit-testable against
committed golden vectors, mutation-testable (`P3.14`), and independent of every library that will change
underneath it. `ADR-013` made scoring a pure function for the same reason — a wrong-but-plausible result is
indistinguishable from a right one without it. Tamper evidence has exactly that property.

### Confirmed implementation decisions

| Decision                       | Choice                                                                                                                                                                                                                                | Consequence                                                                                                                                                                                        |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Layering                       | `AuditEmitter` port + `AuditEvent` + `RetentionClass` in `shared.kernel`; chain algebra pure in `audit.domain`; R2DBC, KMS and verifier adapters in `audit.infra`; compliance query as a slice in the `audit` module                    | A slice emits through the kernel without importing the `audit` module, so rule R2 holds and the algebra is testable with no database                                                                |
| Canonical serialisation        | A **frozen, versioned codec** owned by `audit.domain`: sorted keys, UTF-8 NFC, RFC 3339 UTC at fixed precision, canonical number forms, explicit null handling, and a `hash_algo_version` column on every row. Golden vectors committed | `canonical_json` is **undefined** in the architecture (`TASK-AUD1-DEFECT-001`). A library upgrade that reorders a key silently invalidates five years of hashes and raises a false **P1** tamper alarm |
| Append-only scope              | The `UPDATE`/`DELETE` prohibition and the trigger are scoped to **`audit.audit_event`**. The chain *anchors* need writes: `audit_chain_head` is an upsert and `audit_chain_root_head` a compare-and-swap `UPDATE`                       | The architecture's grant matrix forbids the exact statement its own chain design mandates on the hot path — `TASK-AUD1-BLOCKER-001`. Resolved per table, not per schema                              |
| Grants, per table              | `INSERT` only on `audit_event`, `audit_chain_checkpoint`, `audit_chain_seal`; `INSERT`+`UPDATE` on `audit_chain_head` for module and composite roles; `UPDATE` on `audit_chain_root_head` for the **sealer role only**; `DELETE` for the retention role on expired partitions only | Makes `FEAT-PLAT-002/tasks.md` `P7.8` and `P7.15` amendable rather than simply wrong, and keeps the privilege minimal at each table                                                                          |
| Shard topology                 | `shard_id = hash(entity_id) mod N`, `N = 64`, fixed per tenant at provisioning, changeable only at an epoch boundary as a recorded policy change. `shard_id` is a **column**, never a partition key                                     | Per-shard contention at worst-case one-tenant load falls from ~1,110 to ~17 writes/s (`ARC-AUD-005`), and the partition count stays 4 × 60 = 240 rather than multiplying by 64                       |
| Head-upsert ordering           | The chain-head upsert is the **last statement** of the business transaction, asserted by a test rather than left as a comment                                                                                                           | The shard lock is then held for the commit path only. Ordering is invisible to any database constraint, so it needs its own assertion (`TASK-AUD1-DEFECT-007` limb)                                  |
| Empty shard in a seal          | An empty shard contributes a defined **sentinel hash** and a zero count, both recorded in `per_shard_counts`                                                                                                                            | `ARC-AUD-005`'s root formula concatenates all `N` shard heads without saying what an unused shard contributes — likely for every low-volume tenant (`TASK-AUD1-DEFECT-004`)                          |
| Root append                    | Compare-and-swap on `audit_chain_root_head`; zero rows affected means another sealer won, so the loser rolls back, re-reads and **re-derives**. Never a data repair                                                                     | `ARC-AUD-007`. The retry is a pure recomputation because the loser's shard heads are untouched — which is only true because the algebra is a function of its inputs                                  |
| Close ordering                 | Canonical `(period, retention_class)` order, one seal per transaction                                                                                                                                                                   | A re-run close reproduces the same root sequence. Deterministic, not semantic — the chain claims nothing about one class preceding another                                                           |
| Retention class                | Resolved by the **emitter at write time** by longest-wins against the `retention_policy` version in force, and written onto the row. Reclassification is a **new event**, never an `UPDATE`                                             | `BR-AUD-002`, `ARC-DATA-030`. Placement is physical, so a query-time computation could not make a partition retention-homogeneous                                                                     |
| Anchors outlive the data       | `audit_chain_seal` and `audit_chain_checkpoint` carry the **longest** class and are never pruned with the events they cover                                                                                                             | A disposed epoch still verifies standalone and the root chain stays unbroken across the gap — under 200 MB per 100 tenants over five years (§15.5)                                                    |
| Emission failure               | **Fails the business transaction.** No degraded mode, no async fallback, no dropped event, no `REQUIRES_NEW`                                                                                                                            | The plan's reliability expectation. "Audit succeeded but the change did not" and its converse are both excluded by construction                                                                      |
| Seal failure                   | A KMS outage **defers the seal** and raises an alert; it never blocks a business write                                                                                                                                                  | Sealing is off the hot path by design, so its failure mode must stay off it too. An unsealed epoch blocks *disposition*, which is the correct thing to block                                          |
| Secret check                   | Runs in **all** environments, rejects on field **name** with word-boundary matching and an enumerated permitted-`key` allowlist                                                                                                          | §9.5's list includes `key`, but `ARC-DATA-030` step 3 **requires** `AUDIT_EPOCH_DISPOSED` to record `policy_key` — the check as written rejects a payload the protocol mandates (`TASK-AUD1-DEFECT-003`) |
| Verifier discipline            | Evidence is **preserved, never repaired**; a fork is never reconciled into a preferred branch                                                                                                                                            | `ARC-AUD-006`. A repaired chain proves nothing, so "fix it" is the one response the runbook must forbid                                                                                              |

### Assumptions

1. **The schema, grants and trigger ownership are settled.** `FEAT-PLAT-002/tasks.md` `P2.3` and `P3.9` shipped
   the `audit` schema and the `ALTER DEFAULT PRIVILEGES … GRANT INSERT ON TABLES` bootstrap so tables created
   here are insertable without further grants; `P7.15` asserts that contract end to end. Architecture §9.5
   defines the `BEFORE UPDATE OR DELETE` trigger convention, while the plan ownership matrix assigns the
   audit trigger to this feature; `P2.5` and `P3.8` own its design and implementation here.
2. **`ActorContext` and `SecretFieldPattern` are the kernel's.** `FEAT-PLAT-003 tasks.md` `P4.2`, `P4.14` and
   `P8.5` deliver them; that task list explicitly excluded `AuditEmitter` and assigned the port and its
   implementation here.
3. **The R8 rule already exists but does not yet bite.** `FEAT-PLAT-001 tasks.md` `P4.25` shipped the
   `ARC-VERIFY-010` static limb — a handler mutating a tenant-scoped aggregate emits ≥1 audit event in the
   same transaction — and recorded that "the emitter it checks for" does not exist. This feature supplies
   the emitter and proves the rule detects a real emission and rejects a real omission.
4. **`audit.audit_event` is the table name**, per §9.2 and the grant matrix, superseding §8.4's
   `audit.event`. Carried forward from `FEAT-PLAT-002/tasks.md` and `FEAT-PLAT-003 tasks.md` (`TASK-PLAT3-DEFECT-004`).
5. **Retention *execution* is `FEAT-PRIV-001`'s.** This feature ships the placement key, the anchors, the
   ordered disposition protocol and the hold-suspension mechanism; the engine that schedules dispositions
   and the policy-version store are not here. `ARC-VERIFY-032` is shared (plan §14.4).
6. **Per-feature audit events are not here.** This feature ships the catalogue *convention*; each later
   feature declares its own events against it and inherits the coverage rule.
7. **Metric registration goes through `FEAT-OBS-001`.** `FEAT-OBS-001 tasks.md` `P2.7` and `P4.16` own the naming
   convention and the label-cardinality guard this feature's metrics must satisfy.

### Blockers and defects carried into this task list

| ID                       | Statement                                                                                                                                                                                                                                                                                                                                              | Owning task        |
|--------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|
| `PLAN-BLOCKER-001`       | `ci/architecture-ratification.json` is `status: RATIFIED`; plan §10 gates Phase 0 **implementation** on stage 4a. Discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7`; not restated here                                                                                                                                                                        | `P0.1`             |
| `TASK-AUD1-BLOCKER-001`  | **The grant matrix forbids the chain design's own hot-path statement.** §9.2 (line 1170), the composite-role row (line 1171) and §8.3 (line 1016) all state `INSERT` on `audit.audit_event` and **no `UPDATE`/`DELETE` on `audit.*`** — while `ARC-AUD-005` requires `INSERT … ON CONFLICT DO UPDATE` on `audit.audit_chain_head` **inside the business transaction**, and `ARC-AUD-007` a CAS `UPDATE` on `audit.audit_chain_root_head`. `FEAT-PLAT-002/tasks.md` `P7.8` and `P7.15` already encode the prohibition as **integration tests**, so shipping the chain head either fails every audited write at runtime or breaks the build. Resolved per table in `P2.5`; both sibling assertions must be amended | `P0.4`, `P2.5`, `P3.8` |
| `TASK-AUD1-DEFECT-001`   | **`canonical_json(event)` is never specified.** §9.5 and `ARC-AUD-005` make `record_hash` depend on exact bytes, retained five years, with no statement of key ordering, timestamp precision, number form, unicode normalisation or a version tag. A serialiser upgrade reorders one key and every later verification fails as tampering                    | `P0.5`, `P2.4`     |
| `TASK-AUD1-DEFECT-002`   | Plan §14.4 assigns "`ARC-VERIFY-010`, `011` audit coverage rule and chain integrity" here, but §19.8 defines `ARC-VERIFY-011` as "no write path without an `ActorContext`", which `FEAT-PLAT-003 tasks.md` adopted as owned (`TASK-PLAT3-DEFECT-003`) and implemented at its `P4.18`/`P7.8`. So `-011` is both mis-described and double-assigned, and **daily chain verification has no register identifier at all** | `P0.7`, `P1.11`    |
| `TASK-AUD1-DEFECT-003`   | §9.5's no-secrets check "fails the emitter in all environments" on a field named like `key`, yet `ARC-DATA-030` step 3 **requires** `AUDIT_EPOCH_DISPOSED` to record the `policy_key` and version applied. The check as written rejects the payload the disposition protocol mandates. Same root cause as `TASK-OBS1-DEFECT-003`, raised jointly           | `P0.6`, `P4.7`     |
| `TASK-AUD1-DEFECT-004`   | `ARC-AUD-005`'s `epoch_root` concatenates `shard_head_1 ‖ … ‖ shard_head_N` without defining what an **empty** shard contributes. With `N = 64` most tenants will close epochs with unused shards, so the root is underdetermined and two implementations disagree                                                                                        | `P0.7`, `P2.7`     |
| `TASK-AUD1-DEFECT-005`   | The feature card's Definition of Done requires conditions **A6** and **A7** discharged, while its own Delivery-phase row says "A6/A7 evidence produced in Phase 6 against the full load profile". A Phase 0 feature cannot discharge a Phase 6 condition; the harness is built here and the discharge is tracked as Phase-6-gated                        | `P2.19`, `P7.24`   |
| `TASK-AUD1-DEFECT-006`   | §9.2's `audit` row lists only `audit_event`, `audit_chain_head`. `ARC-AUD-005` and `ARC-AUD-007` require `audit_chain_checkpoint`, `audit_chain_seal` and `audit_chain_root_head` as well — three tables absent from the schema table the grant matrix was built from                                                                                     | `P0.7`, `P1.8`     |
| `TASK-AUD1-DEFECT-007`   | `N` is "fixed per tenant at provisioning", but no feature owns setting it. `FEAT-TENANT-001` provisions tenant defaults and is Phase 1; its card never mentions a shard count. Without an owner, tenant one is provisioned with whatever the code defaults to and the "recorded policy change" has nothing to change                                       | `P0.8`, `P8.5`     |
| `TASK-AUD1-OBS-001`      | §16.2 defines the integrity metrics but nothing registers them. Two further problems: `audit_chain_head_lock_wait_seconds` is specified "p95/p99 **by shard**", which at 64 shards × tenants collides with `FEAT-OBS-001 tasks.md` `P4.16`'s cardinality guard; and §19.3 scopes CI 6 mutation testing to scoring and state machines, omitting the chain algebra — the one other place a plausible-but-wrong implementation is indistinguishable from a correct one | `P3.14`, `P9.1`, `P9.2` |

---

# Phase 0 – Gate Prerequisites

`PLAN-BLOCKER-001` is discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7` and is **not** restated. Under a
`temporaryArchitectureGate` (`implementationAllowed: false`) only Phase 1 and Phase 2 tasks are authorised.

1. [*] Confirm which authorisation scope is in force from `FEAT-PLAT-001 tasks.md` `P0.5` or `P0.6` and record it. Deliverable: one-line phase-log entry. Acceptance: no Phase 3+ task starts under `implementationAllowed: false`.
2. [*] Confirm `FEAT-PLAT-002` has delivered the `audit` schema and the `ALTER DEFAULT PRIVILEGES` `INSERT` bootstrap, and reconcile the trigger convention with the plan's `FEAT-AUD-001` ownership. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-002 tasks.md` `P2.3`, `P3.9`, `P7.15`.
3. [*] Confirm `FEAT-PLAT-003` has delivered `ActorContext`, `TenantId`, the controlled clock and `SecretFieldPattern`. Deliverable: dependency-satisfied record. Depends on `FEAT-PLAT-003 tasks.md` `P4.2`, `P4.14`, `P8.5`.
4. [*] **Escalate `TASK-AUD1-BLOCKER-001`** to the Architecture Owner, the Security Engineer and the `FEAT-PLAT-002` owner: the grant matrix forbids the chain-head upsert the chain design mandates on the hot path. Deliverable: ratified per-table grant decision plus agreed amendments to `FEAT-PLAT-002 tasks.md` `P7.8` and `P7.15`. Acceptance: settled before `P3.8`; no implementation proceeds against a grant set that fails a sibling's blocking test.
5. [ ] Raise `TASK-AUD1-DEFECT-001` for approval: a frozen, versioned canonical-JSON codec with committed golden vectors and a `hash_algo_version` column. Deliverable: approval record or a named alternative canonicalisation. Acceptance: settled before `P2.4`, because every hash written before the decision would have to be re-derived after it.
6. [ ] Raise `TASK-AUD1-DEFECT-003` to the `FEAT-PLAT-003` owner jointly with `TASK-OBS1-DEFECT-003`, so the pattern is corrected once at its single definition site rather than worked around in two consumers. Deliverable: joint correction record naming `policy_key` explicitly.
7. [ ] Raise `TASK-AUD1-DEFECT-002`, `-004` and `-006` to the Architecture Owner as baseline defects with the resolutions this feature adopts. Deliverable: three gap records. Acceptance: `-004`'s sentinel is settled before `P2.7`, since it changes every root hash.
8. [ ] Obtain the feature's additional Definition of Ready: the shard count `N`, the epoch policy and the canonical close ordering agreed, and the audit-event catalogue convention defined so each later feature can declare its events. Raise `TASK-AUD1-DEFECT-007` in the same session and record who owns provisioning `N`. Deliverable: signed DoR record with a named owner for `N`.
9. [ ] Confirm the universal Definition of Ready (plan §8.0) holds and record any item that does not, with its blocker. Deliverable: signed DoR record.

---

# Phase 1 – Discovery and Analysis

Satisfies the additional Definition of Ready: the shard count, epoch policy, close ordering and catalogue
convention are agreed.

1. [ ] Transcribe the §9.5 property table row by row — append-only, in-transaction, attribution, tamper evidence, queryable, no secrets, privileged reads audited — and mark which task discharges each. Deliverable: property-ownership table. Acceptance: all seven rows have an owning task in this list.
2. [ ] Transcribe the four retention classes with their periods, partition names and requirements: `RESULT_CORRECTION_EVIDENCE` and `RESULT_PUBLICATION_EVIDENCE` (5 years or result lifetime, whichever is longer), `PIN_SECURITY_EVENT` (12 months), `GENERAL_AUDIT_EVENT` (2 years). Deliverable: retention-class card. Acceptance: records that the class is a **physical placement key**, not a query-time computation.
3. [ ] Transcribe the `ARC-AUD-005` topology table: chain identity, shard assignment, write path, interim checkpoint, epoch seal, root-chain append, and the tamper-evidence-preserved argument. Deliverable: topology card.
4. [ ] Transcribe the `ARC-AUD-007` root-append table: root head, the four-step CAS, the dense-sequence property, deterministic ordering, cost, and failure handling. Deliverable: root-append card. Acceptance: records that zero rows affected means another sealer won, and that the retry is a recomputation and never a repair.
5. [ ] Transcribe the `ARC-AUD-006` verifier scope: daily walk of **open** shard chains, sealed epochs checked against their signed roots without re-walking, quarterly full re-walk, re-walk after every restore, plus the root-chain gap-free, duplicate-free and reproduces-from-predecessor assertions. Deliverable: verifier card.
6. [ ] Transcribe the `ARC-DATA-030` four controls and the **five-step pruning protocol in order**, recording that a disposition skipping a step is a failed disposition and not a fast one. Deliverable: disposition card — the acceptance basis for `P7.19`.
7. [ ] Transcribe `ARC-DATA-031` hold suspension: partition promoted to `HOLD_SUSPENDED`, disposition suspended entirely and recorded as a suppressed disposition, release resuming from the original start event without resetting the clock. Deliverable: legal-hold card. Acceptance: records the accepted, bounded, DPO-visible over-retention trade-off (§22.1) and the 90-day **P3**, so it is not later mistaken for a defect.
8. [ ] Transcribe the §9.2 grant-matrix rows touching `audit` for the module role, the composite role and §8.3, and set them against `ARC-AUD-005`'s and `ARC-AUD-007`'s required statements. Deliverable: grant-conflict analysis carrying `TASK-AUD1-BLOCKER-001` and `TASK-AUD1-DEFECT-006`. Acceptance: the analysis names the exact statement each role must execute, per table.
9. [ ] Transcribe the attribution columns — `actor_type`, `actor_id`, `system_actor_name`, `tenant_id` (nullable only for platform-scope actions), `occurred_at`, `correlation_id` — and map each to its kernel source. Deliverable: attribution card. Acceptance: the non-null constraint is recorded as the mechanism for `BR-IAM-001` and `SC-008`, and `tenant_id`'s nullability is recorded as deliberate and narrow.
10. [ ] Enumerate the §16.2 integrity metrics, the four §16.4 audit alerts with their severities and first actions, and the dashboard 7 audit panels. Deliverable: telemetry card carrying `TASK-AUD1-OBS-001`.
11. [ ] Map this feature's verification obligations to their real §19.8 identifiers: `ARC-VERIFY-010` (owned, CI 4 + integration — this feature completes the rule `FEAT-PLAT-001 tasks.md` `P4.25` installed), `ARC-VERIFY-031` (owned, condition **A6**, `L10`), `ARC-VERIFY-032` (shared with `FEAT-PRIV-001`, condition **A7**), and the CI 10 audit-payload leak scan. Deliverable: verification-ownership table recording `TASK-AUD1-DEFECT-002` and that `ARC-VERIFY-011` stays `FEAT-PLAT-003`'s.
12. [ ] List every consumer obligation this feature must satisfy before its dependants start: the `AuditEmitter` port signature, the catalogue convention, the retention-class enumeration, and the disposition protocol `FEAT-PRIV-001` executes. Deliverable: consumer-contract table with the dependant feature and task named per row.

---

# Phase 2 – Architecture and Design

1. [ ] Design the layering: `AuditEmitter`, `AuditEvent`, `RetentionClass` and `EntityRef` in `shared.kernel`; the chain algebra pure in `audit.domain`; R2DBC, KMS and verifier adapters in `audit.infra`; the compliance query as an `audit` slice. Deliverable: layering note plus the conformance rules that enforce it. Acceptance: no module outside `audit` may import `audit.domain` or `audit.infra`, and the kernel port carries no framework type.
2. [ ] Design the `AuditEvent` shape and the `AuditEmitter` port signature with every attribution field a **required parameter**, so there is no no-arg emission path. Deliverable: port and type design note (`BR-IAM-001`, `SC-008`).
3. [ ] Design the audit-event catalogue convention — a stable `event_type` namespace per bounded context, its alignment with `FEAT-OBS-001`'s §16.1 `eventCode`, and how a later feature registers its events. Deliverable: catalogue convention — the feature's additional DoR artifact and the input every later feature consumes.
4. [ ] Specify the canonical serialisation codec: sorted keys, UTF-8 NFC, RFC 3339 UTC at a fixed precision, canonical integer and decimal forms, explicit null-versus-absent handling, and the `hash_algo_version` recorded on every row. Deliverable: canonicalisation specification plus the golden-vector set (`TASK-AUD1-DEFECT-001`). Acceptance: the spec is frozen and versioned, and a new version never rewrites an existing row's hash.
5. [ ] Design the per-table grant set that resolves `TASK-AUD1-BLOCKER-001`, and the table-scoped `BEFORE UPDATE OR DELETE` trigger on `audit_event` only. Deliverable: grant and trigger design note. Acceptance: states explicitly that a schema-wide trigger would block the chain-head upsert, so the scope is a correctness requirement and not a simplification.
6. [ ] Design the chain algebra as pure functions: `record_hash = SHA-256(prev_hash ‖ canonical_json(event))`, shard assignment `hash(entity_id) mod N`, epoch identity `(retention_class, period)`, and the seeding rule for the first record of a new chain. Deliverable: algebra specification with the seed value defined.
7. [ ] Design the epoch-seal derivation including the empty-shard sentinel and `per_shard_counts`. Deliverable: seal specification (`TASK-AUD1-DEFECT-004`). Acceptance: two independent implementations of the spec produce the same root for a sparse epoch.
8. [ ] Design the root compare-and-swap append: the four steps, the observed-`root_seq` predicate, the rollback-and-re-derive path for a loser, and the canonical `(period, retention_class)` close ordering with one seal per transaction. Deliverable: root-append design note.
9. [ ] Design the checkpoint policy: every 10,000 records or hourly, whichever comes first, and always at an epoch boundary, KMS-signed with a key the application cannot use to forge a backdated checkpoint. Deliverable: checkpoint design note.
10. [ ] Design the KMS signing boundary: which workload identity signs, the key policy, and why the application cannot backdate or re-sign. Deliverable: signing design note referencing `ARC-SEC-013` and the §17.5 secret table.
11. [ ] Design write-time retention resolution: longest-wins evaluated against the `retention_policy` version in force, the winning class written onto the row, and reclassification as a **new event**. Deliverable: placement design note plus the interface `FEAT-PRIV-001` supplies the policy version through.
12. [ ] Design the partitioning: `LIST (retention_class)` → `RANGE (occurred_at)` monthly, the 4 × 60 = 240 bound, `shard_id` as a column, and the partition-creation cadence. Deliverable: partitioning design note (`ARC-DATA-030`, §15.5).
13. [ ] Design the verifier: the daily open-chain walk, the sealed-epoch signature check without re-walking, the quarterly and post-restore full re-walks, and the root-chain assertions. Deliverable: verifier design note. Acceptance: the design **preserves** evidence and has no repair path — a fork is never reconciled into a preferred branch.
14. [ ] Design the ordered disposition protocol as an executable sequence with the boundary to `FEAT-PRIV-001` stated: verify, confirm root and signature, emit `AUDIT_EPOCH_DISPOSED` **into the current epoch**, detach, re-verify the retained tail and the root chain across the gap. Deliverable: protocol design note.
15. [ ] Design partition-granular hold suspension: promotion to `HOLD_SUSPENDED`, the suppressed-disposition record with hold reference and applied policy version, and release resuming from the original start event. Deliverable: hold design note (`ARC-DATA-031`, `CR-PRIV-001`).
16. [ ] Design in-transaction emission: the emitter joins the caller's transaction on the same connection, the head upsert is issued last, and no `REQUIRES_NEW`, no new connection and no async path exists. Deliverable: emission design note plus the conformance rules that assert each prohibition.
17. [ ] Design the tenant-scoped compliance query slice: its `Policy`, the three §9.5 indexes it uses, pagination, and the rule that a privileged read of candidate data through it **emits its own audit event**. Deliverable: slice design note (`REQ-AUD-002`, `REQ-PRIV-004`).
18. [ ] Design the audit path against the §15.2 answer-save budget: exactly two of the six statements are audit — the event insert and the head upsert — and no third statement may be added to the hot path. Deliverable: budget conformance note tied to `FEAT-OBS-001 tasks.md` `P7.12`.
19. [ ] Design the **A6/A7 harness** deliverable in Phase 0: the load fixture, the forced concurrent multi-class close, the sealer-kill point between KMS signature and head advance, the mixed-retention disposition fixture with a concurrent seal in flight, and the metrics each run must record. Deliverable: harness specification recording `TASK-AUD1-DEFECT-005` — the harness ships here, the discharge is Phase 6.
20. [ ] Design the failure matrix: emission failure fails the transaction; KMS unavailable defers the seal and alerts without blocking business writes; an unsealed epoch blocks disposition. Deliverable: failure-mode table.

---

# Phase 3 – Data and Infrastructure

1. [ ] Author the `audit.audit_event` migration: attribution columns with their non-null constraints, `retention_class`, `shard_id`, `seq`, `record_hash`, `prev_hash`, `hash_algo_version`, payload, and `LIST (retention_class)` → `RANGE (occurred_at)` partitioning. Deliverable: migration. Acceptance: expand-phase only and forbidden-operation clean per §9.8.
2. [ ] Author the four class partitions and the initial monthly subpartitions, plus the job that creates future subpartitions ahead of need. Deliverable: migration plus scheduled task. Acceptance: a write with no matching subpartition is impossible in normal operation, and the failure mode if one is missing is a refused write, never a misplaced row.
3. [ ] Author the three §9.5 indexes: `(tenant_id, occurred_at DESC)`, `(tenant_id, entity_type, entity_id)`, `(tenant_id, event_type, occurred_at DESC)`. Deliverable: migration. Acceptance: each is justified by a named compliance query in `P2.17`.
4. [ ] Author `audit.audit_chain_head` with PK `(tenant_id, retention_class, period, shard_id)` — the upsert target, one row per open shard. Deliverable: migration.
5. [ ] Author `audit.audit_chain_root_head (tenant_id PRIMARY KEY, root_seq, root_head_hash, sealed_at)` per `ARC-AUD-007`. Deliverable: migration.
6. [ ] Author `audit.audit_chain_checkpoint (tenant_id, retention_class, period, shard_id, seq_end, head_hash, signed_at, signature)`. Deliverable: migration.
7. [ ] Author `audit.audit_chain_seal` holding the epoch identity, `root_seq`, `epoch_root`, `per_shard_counts`, sequence ranges, signature and `signed_at`. Deliverable: migration. Acceptance: retained permanently and independently of the events it covers.
8. [ ] Author the per-table grants from `P2.5` and the table-scoped trigger on `audit_event`, and submit the agreed amendments to `FEAT-PLAT-002/tasks.md` `P7.8` and `P7.15`. Deliverable: grant migration plus two sibling amendment records (`TASK-AUD1-BLOCKER-001`).
9. [ ] Author the retention role's `DELETE` grant, scoped to expired partitions only and held by no application role. Deliverable: grant migration. Acceptance: the application cannot delete an audit row by any code path, which is the claim `P7.3` proves.
10. [ ] Provision the KMS asymmetric signing key with its workload identity and key policy, distinct from the PIN-retrieval KEK. Deliverable: key provisioning record plus the §17.5 secret-table entry.
11. [ ] Confirm `audit.audit_event` is registered in `FEAT-PLAT-005 tasks.md` `P4.4`'s exam-critical table registry, and add the four anchor tables with their migration constraints. Deliverable: registry extension record.
12. [ ] Verify every migration in this feature against CI stage 12: applied to a production-shaped dataset, lock duration measured, no forbidden operation. Deliverable: migration verification result.
13. [ ] Build the Testcontainers fixture seeding all four retention classes across two monthly boundaries and multiple shards, with a subset under legal hold. Deliverable: fixture — the substrate for `P7.17`–`P7.20`.
14. [ ] Wire the CI gates: extend stage 10's secret-leak scan with the **audit payload** limb, add daily chain verification to stage 8, and raise the proposal to add `audit.domain` to stage 6's PIT mutation scope. Deliverable: three gate configurations plus the `TASK-AUD1-OBS-001` proposal. Acceptance: the mutation proposal argues from §19.3's own reasoning — a plausible, symmetric, wrong hash implementation is indistinguishable from a correct one by example-based tests.

---

# Phase 4 – Backend Implementation

1. [ ] Implement `AuditEvent`, `RetentionClass`, `EntityRef` and the `AuditEmitter` port in `shared.kernel` with no framework type. Deliverable: kernel types. Acceptance: the kernel-purity rule stays green, and every attribution field is a required constructor parameter.
2. [ ] Implement the canonical serialisation codec in `audit.domain` to the `P2.4` specification, with `hash_algo_version` emitted alongside every hash. Deliverable: codec. Acceptance: pure, dependency-free of any configurable object mapper, and deterministic across JVM locale and default time zone.
3. [ ] Commit the canonical-JSON golden vectors as data and assert the codec against them. Deliverable: vector set plus its test. Acceptance: a change to the codec that alters any vector fails the build — the mechanism that makes `TASK-AUD1-DEFECT-001` non-recurring.
4. [ ] Implement `record_hash` and the chain-link function as pure functions in `audit.domain`, including the new-chain seed. Deliverable: hash functions.
5. [ ] Implement shard assignment `hash(entity_id) mod N` as a pure function with `N` supplied per tenant. Deliverable: shard function. Acceptance: related events on one entity land on one shard, so a shard chain is an ordered entity history rather than an interleaving.
6. [ ] Implement epoch identity and the `(period, retention_class)` canonical ordering comparator in `audit.domain`. Deliverable: epoch types.
7. [ ] Implement the payload secret check on top of the kernel's `SecretFieldPattern` with word-boundary matching and the permitted-`key` allowlist. Deliverable: payload check (`TASK-AUD1-DEFECT-003`). Acceptance: `policy_key` passes; `pin`, `otp`, `token`, `password`, `secret` and any other `*_key` fail the emitter — in every environment, with no profile that relaxes it.
8. [ ] Implement write-time retention resolution: longest-wins against the policy version in force, writing the winning class and the version onto the row. Deliverable: resolver plus the `FEAT-PRIV-001` interface stub.
9. [ ] Implement the epoch-root derivation including the empty-shard sentinel and `per_shard_counts`, as a pure function. Deliverable: root function (`TASK-AUD1-DEFECT-004`).
10. [ ] Implement the R2DBC audit-event insert adapter in `audit.infra`, executing on the caller's connection inside the caller's transaction. Deliverable: insert adapter.
11. [ ] Implement the chain-head upsert as a single `INSERT … ON CONFLICT DO UPDATE … RETURNING`, issued as the **last statement** of the business transaction. Deliverable: upsert adapter.
12. [ ] Implement the ordering guarantee for `P4.11`: a transaction-synchronisation hook that defers the head upsert to just before commit, so ordering is structural rather than a convention each slice must remember. Deliverable: ordering mechanism.
13. [ ] Implement the in-transaction emitter binding the port to the two adapters, with the actor context, tenant and correlation identifier taken from the kernel. Deliverable: emitter. Acceptance: an emission failure propagates and fails the business transaction — asserted by `P7.5`.
14. [ ] Implement the conformance rules that keep emission in-transaction: no `REQUIRES_NEW` on the audit path, no separate connection, no async or event-listener emission. Deliverable: three rules in the CI stage 4 suite with their failure messages.
15. [ ] Complete the `ARC-VERIFY-010` coverage rule that `FEAT-PLAT-001 tasks.md` `P4.25` installed: bind it to the real emitter and confirm it detects a genuine emission and rejects a genuine omission. Deliverable: rule completion record plus both cases (`TASK-AUD1-DEFECT-002`).
16. [ ] Implement the KMS signer adapter in `audit.infra` for checkpoints and seals. Deliverable: signer adapter. Acceptance: the application holds no key material and cannot re-sign an existing checkpoint.
17. [ ] Implement the interim checkpoint writer on the `P2.9` policy — every 10,000 records or hourly, and always at an epoch boundary. Deliverable: checkpoint writer.
18. [ ] Implement the epoch sealer: derive the root, KMS-sign it, and in one transaction insert `audit_chain_seal` and advance `audit_chain_root_head` by compare-and-swap. Deliverable: sealer.
19. [ ] Implement the sealer's loser path: zero rows affected rolls back, re-reads `(root_seq, root_head_hash)` and re-derives, counting the retry. Deliverable: retry path. Acceptance: the retry recomputes and never repairs data, and the shard heads are untouched by the loss.
20. [ ] Implement the multi-epoch close in canonical `(period, retention_class)` order, one seal per transaction, so a re-run reproduces the same root sequence. Deliverable: close ordering.
21. [ ] Implement seal-failure handling: a KMS outage defers the seal and alerts, never blocking a business write, and leaves the epoch unsealed and therefore undisposable. Deliverable: failure path.
22. [ ] Implement the daily verifier walking open shard chains and checking each sealed epoch against its signed root without re-walking it. Deliverable: verifier job on the background role.
23. [ ] Implement the verifier's root-chain assertions: `root_seq` gap-free and duplicate-free per tenant, and each `epoch_root` reproducing from its recorded predecessor and sequence position. Deliverable: root assertions.
24. [ ] Implement the quarterly and post-restore full re-walk of every retained epoch. Deliverable: full-verification job plus its `FEAT-OPS-003` invocation hook.
25. [ ] Implement the verifier's evidence-preservation path: a mismatch records the finding, raises a **P1** and halts, with **no** repair or reconciliation code path present. Deliverable: preservation path. Acceptance: asserted by `P7.14` — the absence of a repair path is the requirement, so the test proves the absence.
26. [ ] Implement the ordered disposition protocol as an executable five-step sequence that aborts on any failed step. Deliverable: disposition executor invoked by `FEAT-PRIV-001`.
27. [ ] Implement the `AUDIT_EPOCH_DISPOSED` event written **into the current epoch**, recording the disposed epoch, sequence ranges, root hash, signature reference and the `policy_key` and version applied. Deliverable: disposal event. Acceptance: exercises the `policy_key` allowlist from `P4.7`, which is why the two are the same feature's work.
28. [ ] Implement partition-granular hold suspension: promotion to `HOLD_SUSPENDED`, the suppressed-disposition record, and release resuming from the original start event without resetting the clock. Deliverable: hold mechanism.
29. [ ] Implement the tenant-scoped compliance query slice with its `Policy`, pagination and the three indexes. Deliverable: slice plus its OpenAPI contract.
30. [ ] Implement privileged-read auditing on the compliance query and the `*_READ` event convention for candidate personal data, PIN retrieval and operator views of grading failures. Deliverable: read-auditing implementation (`REQ-PRIV-004`, `REQ-SEC-014`).
31. [ ] Implement the per-tenant shard-count parameter with its provisioning default, the epoch-boundary-only change rule, and the policy-change record. Deliverable: parameter plus the interface handed to `FEAT-TENANT-001` (`TASK-AUD1-DEFECT-007`).

---

# Phase 5 – Frontend Implementation

**Not applicable.** `FEAT-AUD-001` is a backend platform feature. It does add one API surface the workforce
volumes will consume — the tenant-scoped compliance audit query of `P4.29` — whose contract is published in
`P8.3`. Its consumers are `FEAT-OPS-002`'s operator surfaces and the compliance reporting volume, both
outside Phase 0. The audit **trail viewer** is not built here.

---

# Phase 6 – Security and Hardening

The append-only claim is the platform's tamper-evidence argument, so each layer of it is proven adversarially
rather than reviewed.

1. [ ] Attempt an `UPDATE` and a `DELETE` on `audit.audit_event` as every application role and every composite role, and confirm each is refused for want of privilege. Deliverable: privilege-refusal matrix.
2. [ ] Repeat both attempts with the trigger as the only remaining barrier — as a role that has been granted `UPDATE` in a test fixture — and confirm the trigger raises. Deliverable: trigger backstop evidence. Acceptance: proves the second layer independently, rather than inferring it from the first.
3. [ ] Attempt to delete an audit row through the ORM, a native query, a batch operation and a cascade from a parent delete, and confirm each is refused. Deliverable: four adversarial attempts (`REQ-AUD-003`).
4. [ ] Confirm the chain-anchor grants are the minimum from `P2.5`: no application role can `UPDATE` `audit_chain_root_head`, and no role can `UPDATE` a checkpoint or a seal. Deliverable: anchor privilege review.
5. [ ] Attempt to emit an audit payload containing a PIN, an OTP, a token and a password, each as a top-level field, a nested field and an exception message, and confirm the emitter rejects every case in a production-profile run. Deliverable: twelve adversarial attempts (`REQ-SEC-004`, `REQ-RSLT-021`).
6. [ ] Confirm the payload check cannot be relaxed by profile, property or feature flag, and that no such switch exists. Deliverable: absence-of-override review.
7. [ ] Attempt a cross-tenant audit read through the compliance query and confirm a non-disclosing refusal, and that RLS returns zero rows if the tenant predicate is omitted. Deliverable: tenant-isolation evidence for the new endpoint plus its isolation-matrix entry.
8. [ ] Confirm a privileged read through the compliance query emits its own audit event, so diagnosis is not an unaudited disclosure route. Deliverable: read-audit evidence (`REQ-PRIV-004`).
9. [ ] Attempt to forge a backdated checkpoint and a re-signed seal using the application's own credentials, and confirm KMS refuses. Deliverable: two signing-boundary attempts.
10. [ ] Confirm `tenant_id` is null only for genuinely platform-scope actions, by enumerating every emission site that passes null. Deliverable: nullability audit. Acceptance: an enumerated list, not a convention — a null tenant is how a tenant-scoped action escapes tenant-scoped review.
11. [ ] Review the audit store against the §13.6 threat rows for repudiation and tampering and record how each is mitigated or where it is carried. Deliverable: threat-model conformance record.
12. [ ] Confirm no credential, key material or KMS secret exists in source or configuration, and that the signing identity resolves through workload identity. Deliverable: secret-scan result plus configuration review.

---

# Phase 7 – Testing and Quality Assurance

The distinguishing obligation: the chain is a proof, so its tests must establish that a **wrong** chain is
detected, not merely that a right one verifies. Conditions **A6** and **A7** are Phase-6-gated
(`TASK-AUD1-DEFECT-005`); the harness and every non-load assertion are delivered here.

1. [ ] Unit-test the canonical codec against the committed golden vectors, including key ordering, unicode normalisation, timestamp precision, decimal form and null handling. Deliverable: codec test suite.
2. [ ] Test codec determinism across a non-UTC default time zone, a non-English default locale and a JVM restart. Deliverable: three determinism tests. Acceptance: these are the ambient-state failures that would make a hash irreproducible years later, so each is asserted rather than assumed.
3. [ ] Integration-test append-only against real PostgreSQL: the grant refusal and the trigger backstop, per `P6.1` and `P6.2`, retained as the `ARC-VERIFY-010` integration limb. Deliverable: integration test.
4. [ ] Test the `ARC-VERIFY-010` coverage rule both ways: a handler that mutates a tenant-scoped aggregate without emitting fails the build, and one that emits passes. Deliverable: positive and negative rule tests. Acceptance: this is what turns `FEAT-PLAT-001 tasks.md` `P4.25` from a rule with nothing to check into a rule that bites.
5. [ ] Test that an audit emission failure rolls back the business change, and that a business rollback discards the audit record — both directions of the in-transaction guarantee. Deliverable: two integration tests (`REQ-AUD-001`, P7).
6. [ ] Add negative tests for each `P4.14` prohibition: a `REQUIRES_NEW` audit path, a separate-connection emission and an async emission each fail the build. Deliverable: three negative tests.
7. [ ] Test that the chain-head upsert is the last statement of the transaction, by capturing statement order on a real connection. Deliverable: ordering test. Acceptance: asserted against captured statement order, not by reading the code.
8. [ ] Test the chain link function: a tampered payload, a reordered pair of records and a substituted `prev_hash` each fail verification. Deliverable: three tamper-detection tests — the assertions that make the chain a proof rather than a checksum.
9. [ ] Test shard assignment: related events on one entity share a shard, and the distribution over many entities is even enough that no shard carries a disproportionate share. Deliverable: assignment tests plus a recorded skew figure.
10. [ ] Test epoch-root derivation for a fully populated epoch and for a **sparse** epoch with empty shards, asserting the sentinel is applied and the root is reproducible. Deliverable: two root tests (`TASK-AUD1-DEFECT-004`).
11. [ ] Test the root CAS append under concurrency: two sealers racing on one tenant produce one winner, one counted retry, a dense `root_seq` and no sibling root. Deliverable: concurrency test.
12. [ ] Test the sealer killed **between the KMS signature and the head advance**, asserting the epoch re-seals cleanly on restart with no gap and no duplicate. Deliverable: kill-point test — the `ARC-VERIFY-031` limb that does not need load.
13. [ ] Test the canonical close ordering: a re-run of a multi-epoch close reproduces the same root sequence. Deliverable: determinism test.
14. [ ] Test the verifier's failure behaviour: a broken link, a missing sequence, a failed signature, a root that does not reproduce from its shard heads, and two roots at one `root_seq` each raise a **P1** and halt with evidence preserved. Deliverable: five verifier tests. Acceptance: each asserts that **no** repair occurred — the absence of a repair path is the requirement.
15. [ ] Test that the daily verifier walks only open chains and checks sealed epochs by signature, by asserting its work does not grow with total retained history. Deliverable: verifier scope test with recorded cost at two history sizes.
16. [ ] Test write-time retention resolution: longest-wins applied at placement, the stored class matching a re-evaluation under the policy version in force, and reclassification appearing as a new event rather than an `UPDATE`. Deliverable: three placement tests.
17. [ ] Test partition homogeneity: no partition contains two retention classes, and every partition is detachable as a unit. Deliverable: partitioning test against the `P3.13` fixture.
18. [ ] Test the ordered disposition protocol step by step, asserting that skipping any step aborts the disposition. Deliverable: protocol tests, one per skipped step.
19. [ ] Test disposition end to end on the `P3.13` fixture: the expired epoch verified and its seal confirmed **before** detach, `AUDIT_EPOCH_DISPOSED` recorded with epoch, sequence range, root hash and `policy_key` + version, and every retained epoch **and the root chain** still verifying afterwards. Deliverable: disposition test — the `ARC-VERIFY-032` integration limb.
20. [ ] Test disposition with a concurrent epoch seal **in flight**, asserting the retained tail, the seal being appended and the root chain all still verify. Deliverable: interleaving test (`ARC-VERIFY-032`, v1.3 addition).
21. [ ] Test legal hold: the covered partition promoted to `HOLD_SUSPENDED` with an audited suppressed disposition, and release resuming from the original start event without resetting the clock. Deliverable: hold tests.
22. [ ] Test the compliance query slice: authorization, tenant scoping, pagination, index usage, and its own read-audit emission. Deliverable: slice test suite.
23. [ ] Assert the audit path costs exactly two statements on the answer-save route, against `FEAT-OBS-001 tasks.md` `P7.12`'s budget assertion. Deliverable: budget conformance result (§15.2).
24. [ ] Deliver the **A6/A7 harness** from `P2.19` as runnable, with a smoke run at reduced scale proving it records answer p95, chain-head lock wait p95/p99, shard skew, audit share of transaction time, CAS retry counts and `root_seq` density. Deliverable: harness plus smoke-run report. Acceptance: the reduced-scale run does **not** discharge A6 or A7; it proves the harness measures what §14.5 requires, so the Phase 6 run cannot pass while recording nothing.
25. [ ] Run the audit-payload limb of the CI stage 10 secret-leak scan and confirm a clean baseline. Deliverable: scan result, BLOCKING.
26. [ ] Register the retained artifacts in the §19.9 verification evidence register: the append-only integration report, the disposition report, the harness smoke report and the daily verification result. Deliverable: register entries, noting that daily chain verification carries no `ARC-VERIFY` identifier (`TASK-AUD1-DEFECT-002`).
27. [ ] Run the full pipeline on a clean checkout and confirm stages 4, 8, 10 and 12 are blocking and green for this feature's contributions. Deliverable: pipeline run record referenced by the Phase 0 exit criteria.
28. [ ] Verify each acceptance outcome in the `FEAT-AUD-001` feature card against a named task and its evidence. Deliverable: completed acceptance-outcome verification table.

---

# Phase 8 – Deployment and Release

1. [ ] Publish the `AuditEmitter` port and the audit-event catalogue convention as the normative platform interface every later feature declares against. Deliverable: interface publication record.
2. [ ] Publish the retention-class enumeration and the write-time placement rule for `FEAT-PRIV-001` and `FEAT-CORR-004` to consume. Deliverable: two interface handover records.
3. [ ] Publish the compliance audit query contract and its OpenAPI component. Deliverable: contract record. Acceptance: passes the CI stage 9 breaking-change diff as a new, additive surface.
4. [ ] Hand the ordered disposition protocol and the hold-suspension mechanism to `FEAT-PRIV-001`, with `ARC-VERIFY-032`'s shared ownership stated. Deliverable: handover record.
5. [ ] Hand the per-tenant shard-count parameter and its provisioning default to `FEAT-TENANT-001`, and record the owner agreed in `P0.8`. Deliverable: handover record closing `TASK-AUD1-DEFECT-007`.
6. [ ] Hand the integrity metric set to `FEAT-OBS-001` for registration under its conventions, and the four §16.4 alerts and dashboard 7 panels to `FEAT-OPS-004`. Deliverable: two handover records.
7. [ ] Hand the post-restore full re-walk hook to `FEAT-OPS-003` as a step in its restore verification. Deliverable: handover record.
8. [ ] Confirm the sealer and verifier run as scheduler singletons on the background role, and that neither runs on the request role. Deliverable: role placement review against `FEAT-PLAT-006`. Acceptance: two replicas of the background role must not double-seal an epoch — the CAS makes that safe, and this task confirms it is also not routine.
9. [ ] State the rollback path: migrations are expand-only and a code rollback is safe because *N−1* runs against *N*'s schema; a **contract**-phase audit migration is never rolled back, and the `hash_algo_version` column is the mechanism that lets a codec change roll forward without invalidating history. Deliverable: rollback statement.
10. [ ] Record the deferrals with their owning features: retention execution and the policy-version store (`FEAT-PRIV-001`); per-feature audit events (each owning feature); dashboards and alert rules (`FEAT-OPS-004`); the audit trail viewer (`FEAT-OPS-002`); A6/A7 discharge (Phase 6, `TASK-AUD1-DEFECT-005`). Deliverable: deferral register.

---

# Phase 9 – Monitoring and Operations

1. [ ] Register the §16.2 integrity metrics through `FEAT-OBS-001`'s registry: `audit_chain_verification_result`, `audit_emit_failure_total`, `audit_checkpoint_overdue_count`, `audit_epoch_seal_total` by result, `audit_epoch_disposition_total` by outcome, `audit_epoch_seal_cas_retry_total`, `audit_root_chain_fork_detected_total`, `audit_root_chain_seq`. Deliverable: metric registrations plus evidence of increment.
2. [ ] Register `audit_chain_head_lock_wait_seconds` and `audit_chain_shard_skew` in a form that satisfies `FEAT-OBS-001 tasks.md` `P4.16`'s cardinality guard — percentiles over shards with shard as an exemplar, not one series per shard. Deliverable: registration plus the resolution record for `TASK-AUD1-OBS-001`. Acceptance: 64 shards × tenants would otherwise be a per-series explosion on the hot path's own health metric.
3. [ ] Confirm `audit_root_chain_fork_detected_total` is wired so a non-zero value is impossible to miss, and that it is zero on a clean baseline. Deliverable: fork-counter evidence (`ARC-AUD-007`).
4. [ ] Confirm the daily verification result is published and that a failure raises the **P1** with evidence preserved. Deliverable: daily verification evidence — a Phase 0 exit criterion and part of this feature's Definition of Done.
5. [ ] Hand the four §16.4 audit alerts to `FEAT-OPS-004` with their conditions, severities and first actions intact: audit emit failure (**P1**), audit chain broken (**P1**), epoch-seal CAS starvation (**P2**), chain-head contention (**P2**), plus checkpoint overdue (**P2**), disposition `VERIFY_FAILED` (**P1**) and partition held beyond 90 days (**P3**). Deliverable: alert handover record.
6. [ ] Request the dashboard 7 audit panels: chain verification history, epoch lifecycle with sealed, disposed and `HOLD_SUSPENDED` counts and ages, and the chain-head lock-wait and shard-skew trend. Deliverable: panel request to `FEAT-OPS-004` (§16.5).
7. [ ] Write the chain-break runbook. Deliverable: `docs/runbooks/audit-chain-break.md`. Acceptance: its first instruction is to preserve evidence and escalate, and it states plainly that the chain is **never** repaired and a fork is never reconciled into a preferred branch — the one action that would destroy the value of the alert.
8. [ ] Write the CAS-starvation runbook: why retries are normal, when sustained retries mean the canonical close ordering is not being applied, and why unsealed epochs then block disposition. Deliverable: `docs/runbooks/audit-seal-starvation.md`.
9. [ ] Write the chain-head contention runbook: read shard skew before answer latency breaches, and treat a hot shard as a wrong shard function or a wrong `N` for that tenant's write mix. Deliverable: `docs/runbooks/audit-chain-contention.md`.
10. [ ] Write the disposition-failure runbook: a `VERIFY_FAILED` outcome means disposition halted **before** detaching, as designed, and is a potential integrity event rather than a job failure. Deliverable: `docs/runbooks/audit-disposition-failed.md`.
11. [ ] Write the held-partition runbook for the 90-day **P3**: the bounded over-retention is an accepted trade-off, and the review question is whether the hold is still required. Deliverable: `docs/runbooks/audit-partition-held.md`.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the audit-event catalogue convention as the normative contract every later feature declares against. Deliverable: `docs/audit-event-catalogue.md` — the artifact that makes the coverage rule satisfiable.
2. [ ] Publish the canonical serialisation specification with its golden vectors and versioning rule. Deliverable: `docs/audit-canonical-json.md`. Acceptance: states that a codec change is a new version and never a rewrite of an existing hash.
3. [ ] Publish the chain topology and proof argument: sharded writes, signed epoch roots, one per-tenant root chain, and why sharding parallelises the write and not the proof. Deliverable: `docs/audit-chain-design.md`.
4. [ ] Publish the per-table grant model and the reason the append-only prohibition is table-scoped rather than schema-scoped. Deliverable: `docs/audit-grants.md` (`TASK-AUD1-BLOCKER-001`). Acceptance: a reader can see why `audit_chain_head` is writable without concluding the audit trail is.
5. [ ] Write the emission authoring guide for slice authors: how to emit, why every attribution field is required, why there is no async path, and what the coverage rule will reject. Deliverable: `docs/audit-emission-authoring.md`.
6. [ ] Publish the disposition protocol and the hold-suspension semantics, including the accepted bounded over-retention trade-off. Deliverable: `docs/audit-disposition.md` — the input to the quarterly compliance audit.
7. [ ] Publish the verification model: daily open-chain walk, quarterly and post-restore full re-walks, and the preserve-never-repair rule. Deliverable: `docs/audit-verification.md`.
8. [ ] Document the compliance query surface for auditors and operators, including that logs are not the audit trail. Deliverable: `docs/audit-query.md` (`REQ-AUD-002`).
9. [ ] Document the A6/A7 evidence obligation for Phase 6, with §14.5's warning stated in full: a run that meets the latency target **without recording contention** does not discharge A6. Deliverable: `docs/audit-a6-a7-evidence.md` (`TASK-AUD1-DEFECT-005`).
10. [ ] Raise `TASK-AUD1-BLOCKER-001`, `TASK-AUD1-DEFECT-001` through `-007` and `TASK-AUD1-OBS-001` to the Architecture Owner as baseline defects, each with the resolution this feature adopted, and raise the two sibling amendments to the `FEAT-PLAT-002` owner. Deliverable: nine defect records plus two amendment records.
11. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, verification identifiers, conditions A6 and A7 with their Phase 6 gating, and retained artifacts. Deliverable: updated matrix rows.
12. [ ] Run a walkthrough with the engineering team covering the in-transaction rule, the catalogue convention, the pure chain algebra, the canonical codec's fragility, and why the chain is never repaired. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                              | Architecture reference | Tasks                                                          | Verification                                                        |
|---------------------------------------------------------------------|------------------------|----------------------------------------------------------------|---------------------------------------------------------------------|
| `REQ-AUD-001` audit inside the business transaction                  | §9.5                   | `P2.16`, `P4.10`–`P4.14`, `P7.5`, `P7.6`                       | Both rollback directions tested; three prohibitions as CI 4 rules   |
| `REQ-AUD-002` queryable, tenant-scoped, separate from logs           | §9.5                   | `P2.17`, `P3.3`, `P4.29`, `P7.22`                              | Slice suite plus the log/audit separation in `FEAT-OBS-001 tasks.md` `P6.10` |
| `REQ-AUD-003`, `BR-AUD-001` append-only and unmodifiable             | §9.5                   | `P2.5`, `P3.8`, `P3.9`, `P6.1`–`P6.3`, `P7.3`                  | Privilege refusal + trigger backstop, proven independently          |
| `BR-AUD-002` longest-wins retention                                  | §9.5, §9.7             | `P1.2`, `P2.11`, `P4.8`, `P7.16`                               | Placement matches re-evaluation under the policy version in force   |
| `BR-IAM-001`, `SC-008` every action attributable                     | §9.5                   | `P1.9`, `P2.2`, `P4.1`, `P4.13`, `P6.10`                       | Non-null attribution; `ARC-VERIFY-010` coverage rule                |
| `REQ-SEC-004`, `REQ-RSLT-021` no secrets in audit payloads            | §9.5                   | `P4.7`, `P6.5`, `P6.6`, `P7.25`                                | CI 10 audit limb, BLOCKING; twelve adversarial attempts             |
| `REQ-PRIV-004`, `REQ-SEC-014` privileged reads audited                | §9.5                   | `P4.30`, `P6.8`                                                | Read-audit emission on the compliance query                         |
| `ADR-011` / `ARC-AUD-001` append-only, in-transaction, tamper-evident | §9.5                   | `P3.1`, `P4.4`, `P4.13`                                        | Conditional on `ARC-VERIFY-031` (condition **A6**)                  |
| `ARC-AUD-005` sharded chain, one signed root per epoch               | §9.5                   | `P1.3`, `P2.6`, `P2.7`, `P4.5`, `P4.9`, `P4.11`, `P4.18`       | Tamper-detection and sparse-epoch root tests                        |
| `ARC-AUD-006` verifier scope and preserve-never-repair               | §9.5                   | `P1.5`, `P2.13`, `P4.22`–`P4.25`, `P7.14`, `P7.15`             | Five verifier failure tests, each asserting no repair occurred      |
| `ARC-AUD-007` CAS-ordered root append                                | §9.5                   | `P1.4`, `P2.8`, `P4.18`–`P4.20`, `P7.11`–`P7.13`               | Concurrency, kill-point and close-ordering determinism tests        |
| `ARC-DATA-030` disposable audit evidence                             | §9.7                   | `P1.6`, `P2.12`, `P2.14`, `P3.1`, `P3.2`, `P4.26`, `P4.27`, `P7.17`–`P7.19` | `ARC-VERIFY-032` integration limb (condition **A7**)      |
| `ARC-DATA-031` partition-granular legal hold                         | §9.7                   | `P1.7`, `P2.15`, `P4.28`, `P7.21`                              | Hold suspension, suppressed disposition, clock not reset            |
| §15.2 answer-save query budget — audit is 2 of 6 statements           | §15.2                  | `P2.18`, `P7.23`                                               | Budget assertion via `FEAT-OBS-001 tasks.md` `P7.12`                        |
| §15.5 anchor sizing and 240-partition bound                           | §15.5                  | `P2.12`, `P3.2`, `P3.7`                                        | Anchors retained past the data they cover                           |
| Canonical serialisation — hashes reproducible for five years          | §9.5                   | `P2.4`, `P4.2`, `P4.3`, `P7.1`, `P7.2`                         | Golden vectors plus three determinism tests                         |
| Clean-architecture layering — port in kernel, algebra pure            | §8.4, §9.5             | `P2.1`, `P4.1`, `P4.4`–`P4.6`, `P4.9`                          | Kernel purity green; algebra tested with no database                |
| `ARC-VERIFY-010` audit coverage rule                                  | §19.8                  | `P4.15`, `P7.3`, `P7.4`                                        | CI 4 BLOCKING; completes `FEAT-PLAT-001 tasks.md` `P4.25`                   |
| `ARC-VERIFY-031` audit write path at load (**A6**)                    | §19.8                  | `P2.19`, `P7.12`, `P7.24`                                      | Harness here; discharge Phase 6 (`TASK-AUD1-DEFECT-005`)            |
| `ARC-VERIFY-032` mixed-retention disposition (**A7**)                 | §19.8                  | `P7.19`, `P7.20`, `P8.4`                                       | Integration limb here; shared with `FEAT-PRIV-001`                  |
| §16.2 integrity metrics and §16.4 alerts                              | §16.2, §16.4           | `P1.10`, `P9.1`–`P9.6`                                         | Registered here; alerts and panels handed to `FEAT-OPS-004`         |
| `PLAN-BLOCKER-001`                                                    | plan §10, §18.3        | `P0.1`                                                          | Discharged by `FEAT-PLAT-001 tasks.md` `P0.1`–`P0.7`                        |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence
from oversight.

| Excluded                                                                                                     | Owner                                              |
|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------|
| The specific audit events each capability emits                                                              | Each owning capability feature                     |
| Retention execution, the retention engine and the `retention_policy` version store                           | `FEAT-PRIV-001`, `FEAT-PRIV-004`                   |
| Result-correction evidence retention following the longer result-evidence period                             | `FEAT-CORR-004`                                    |
| The `audit` schema, its `INSERT` grant bootstrap and RLS                                                     | `FEAT-PLAT-002`                                    |
| `ActorContext`, `TenantId`, the controlled clock and `SecretFieldPattern`                                    | `FEAT-PLAT-003`                                    |
| Module boundaries and the R8 rule's installation (this feature completes it)                                 | `FEAT-PLAT-001`                                    |
| `ARC-VERIFY-011` no write path without an `ActorContext`                                                     | `FEAT-PLAT-003` (`TASK-AUD1-DEFECT-002`)           |
| Structured logging, metric registration conventions and the cardinality guard                                | `FEAT-OBS-001`                                     |
| Dashboards, panels and every alert threshold, severity and routing                                           | `FEAT-OPS-004` (launch condition `L5`)             |
| The A6 and A7 load and disposition **runs** against the full profile                                         | Phase 6 — `FEAT-OPS-005` capacity, this feature's evidence |
| Backup, restore and the post-restore re-walk **invocation**                                                  | `FEAT-OPS-003`                                     |
| The operator audit trail viewer and diagnostic surfaces                                                      | `FEAT-OPS-002`                                     |
| Scheduler singletons, the background role and graceful shutdown                                              | `FEAT-PLAT-006`                                    |
| Per-tenant provisioning of the shard count `N`                                                               | `FEAT-TENANT-001` (`TASK-AUD1-DEFECT-007`, `P8.5`) |
| Ratification of the architecture baseline as a governance act                                                | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] The audit coverage rule is **BLOCKING** and green in CI stage 4 (`P4.15`, `P7.4`).
2. [ ] Daily chain verification is live with a **P1** alert (`P4.22`, `P9.4`, `P9.5`).
3. [ ] The application cannot update or delete an audit record by any code path (`P6.1`–`P6.3`, `P7.3`).
4. [ ] Every business-significant state change and privileged read has an audit event committed with it (`P4.13`, `P4.30`, `P7.4`, `P7.5`).
5. [ ] No audit payload contains a PIN, OTP or other secret (`P4.7`, `P6.5`, `P7.25`).
6. [ ] The per-tenant root chain is single and unforked with a gap-free sequence under concurrent multi-class epoch close (`P4.18`–`P4.20`, `P7.11`–`P7.13`).
7. [ ] A disposed partition leaves every retained epoch and the root chain still verifying (`P4.26`, `P4.27`, `P7.19`, `P7.20`).
8. [ ] Conditions **A6** (`ARC-VERIFY-031`) and **A7** (`ARC-VERIFY-032`) — **Phase-6-gated, not dischargeable here** (`TASK-AUD1-DEFECT-005`). Delivered in Phase 0: the harness (`P2.19`, `P7.24`), the non-load limbs of A6 (`P7.12`) and the A7 integration limbs (`P7.19`, `P7.20`). A6 additionally requires recorded contention, per §14.5 — a run that meets the latency target without recording lock wait and skew does not discharge it (`P10.9`).

### Universal (plan §8.0), as far as this feature can discharge it

9. [ ] All mapped acceptance outcomes verified (`P7.28`).
10. [ ] Unit, slice, integration and contract tests pass; the pure chain algebra is covered without a database (`P7.1`–`P7.13`).
11. [ ] CI stage 4 is green for the code this feature adds, including the three in-transaction rules (`P4.14`, `P7.6`).
12. [ ] Tenant isolation is enforced and the compliance query has an isolation-matrix entry (`P6.7`).
13. [ ] **In-transaction audit emission is discharged here for the whole programme** (`P4.13`) — this feature is the mechanism by which every other feature satisfies that clause of the universal DoD.
14. [ ] Error responses carry a correlation identifier and leak no internal detail — via `FEAT-PLAT-003`'s mapper, with `correlation_id` on every audit row (`P1.9`).
15. [ ] The API contract and OpenAPI document are updated and pass the breaking-change diff (`P8.3`).
16. [ ] Database changes are expand/contract-compliant and pass migration verification at CI stage 12 (`P3.12`).
17. [ ] Required telemetry exists (`P9.1`–`P9.3`); the rollback path is stated (`P8.9`).
18. [ ] No credential, key material or KMS secret exists in source (`P6.12`).
19. [ ] Peer or AI review complete; no unresolved Critical or High defect remains — **`TASK-AUD1-BLOCKER-001` must be closed** (`P0.4`, `P3.8`).
20. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.11`).
21. [ ] **Not dischargeable by this feature, and recorded as such:** conditions A6 and A7 (Phase 6, item 8 above); retention execution and the policy-version store (`FEAT-PRIV-001`); the per-feature audit events (each owning feature); the dashboards and alert rules (`FEAT-OPS-004`); per-tenant provisioning of `N` (`FEAT-TENANT-001`, `TASK-AUD1-DEFECT-007`); `ARC-VERIFY-011` (`FEAT-PLAT-003`, `TASK-AUD1-DEFECT-002`).
