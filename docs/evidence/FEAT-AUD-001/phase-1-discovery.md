# FEAT-AUD-001 Phase 1 Discovery Record

Date: 2026-10-07
Architecture baseline: `arch-v1.4`
Primary sources: architecture sections 8.3-8.4, 9.2, 9.5, 9.7, 16.2,
16.4-16.5, 19.8, and 22.1; plan sections 8.0, 8.1, and 14.4

This is the working artifact for audit tasks `P1.1`-`P1.12`. It preserves
the approved architecture, records known baseline conflicts, and assigns
delivery without designing the Phase 2 solution.

## P1.1 Audit Property Ownership

| Section 9.5 property | Approved implementation | Discharging tasks |
|---|---|---|
| Append-only | Application roles have no `UPDATE` or `DELETE` grant on `audit_event`; a `BEFORE UPDATE OR DELETE` trigger is the database backstop. Only the retention role may delete expired partitions. | `P2.5`, `P3.8`-`P3.9`, `P6.1`-`P6.4`, `P7.3` |
| In-transaction | `AuditEmitter` participates in the slice transaction, so the business change and audit record commit or roll back together. | `P2.16`, `P4.10`-`P4.15`, `P7.4`-`P7.7` |
| Attribution | Every event records `actor_type`, `actor_id`, `system_actor_name`, `tenant_id`, `occurred_at`, and `correlation_id`; an actor is always identifiable. | `P2.2`, `P3.1`, `P4.1`, `P4.13`, `P6.10` |
| Tamper evidence | Events form sharded `(tenant_id, retention_class, period, shard_id)` chains, sealed into one KMS-signed, CAS-ordered root chain per tenant. | `P2.4`, `P2.6`-`P2.10`, `P3.4`-`P3.7`, `P4.2`-`P4.6`, `P4.9`, `P4.16`-`P4.25`, `P7.1`-`P7.15` |
| Queryable, tenant-scoped, separate from logs | Audit uses its own schema and retention classes, three tenant-leading indexes, and a tenant-scoped compliance query; operational logs are never the audit source. | `P2.17`, `P3.3`, `P4.29`-`P4.30`, `P6.7`-`P6.8`, `P7.22` |
| No secrets | A schema check and serialization allowlist reject secret-named fields in every environment. | `P4.7`, `P6.5`-`P6.6`, `P7.25` |
| Privileged reads audited | Candidate-data reads, coordinator PIN retrieval, and grading-failure operator views emit `*_READ` events. | `P2.17`, `P4.30`, `P6.8`, `P7.22` |

All seven approved properties have an implementation and verification owner
in this task list. The append-only scope is `audit_event`, not every table in
the `audit` schema; writable chain anchors are reconciled in `P1.8` and `P2.5`.

## P1.2 Retention-Class Card

`retention_class` is a physical placement key. The emitter resolves
`BR-AUD-002` longest-wins at write time against the policy version then in
force and stores the result on the row; it is not recomputed at query time.

| Retention class | Period | Physical partition | Requirement |
|---|---|---|---|
| `RESULT_CORRECTION_EVIDENCE` | Five years or the result lifetime, whichever is longer | `audit_event_p_correction` | `REQ-RSLT-008`, `REQ-RSLT-034` |
| `RESULT_PUBLICATION_EVIDENCE` | Five years or the result lifetime, whichever is longer | `audit_event_p_publication` | `REQ-RSLT-008`, architecture section 14.1 |
| `PIN_SECURITY_EVENT` | 12 months | `audit_event_p_pin` | Architecture section 14.1 |
| `GENERAL_AUDIT_EVENT` | Two years | `audit_event_p_general` | Architecture section 14.1 |

The outer partition is `LIST (retention_class)` and each class is
subpartitioned monthly by `RANGE (occurred_at)`. Reclassification creates a
new event; append-only history is never updated in place.

## P1.3 ARC-AUD-005 Topology Card

| Topology element | Approved rule |
|---|---|
| Chain identity | `(tenant_id, retention_class, period, shard_id)`; `(retention_class, period)` is the epoch and `period` is the monthly partition key. |
| Shard assignment | `shard_id = hash(entity_id) mod N`, with `N = 64` fixed per tenant at provisioning. Related entity events stay on one shard. `N` may change only at an epoch boundary as a recorded policy change. |
| Write path | Compute `record_hash = SHA-256(prev_hash || canonical_json(event))`, then advance `audit.audit_chain_head` with one `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` as the last business-transaction statement. Worst-case contention falls from about 1,110 to 17 writes/s per shard. |
| Interim checkpoint | Write `audit.audit_chain_checkpoint` every 10,000 records or hourly, whichever comes first, and at every epoch boundary. It records the shard sequence end and head hash and is signed by a KMS key unavailable for application backdating. |
| Epoch seal | At close derive `epoch_root` from the previous root hash, next root sequence, all `N` shard heads, and per-shard counts, then KMS-sign it into `audit.audit_chain_seal`. The explicit sequence preserves one tenant root chain. |
| Root-chain append | Serialize only the low-frequency root-head update through the `ARC-AUD-007` compare-and-swap protocol; no business write takes this lock. |
| Tamper evidence preserved | Sharding parallelizes writes, not proof. Each tenant still has exactly one signed root chain, so forging retained history still requires forging a KMS signature. |

## P1.4 ARC-AUD-007 Root-Append Card

| Root-append element | Approved rule |
|---|---|
| Root head | `audit.audit_chain_root_head (tenant_id PRIMARY KEY, root_seq BIGINT NOT NULL, root_head_hash BYTEA NOT NULL, sealed_at TIMESTAMPTZ NOT NULL)` is the authority on the current root. |
| Compare-and-swap step 1 | Read the observed `(root_seq, root_head_hash)`. |
| Compare-and-swap step 2 | Compute the next `epoch_root` from the observed head, `root_seq + 1`, all shard heads, and `per_shard_counts`. |
| Compare-and-swap step 3 | KMS-sign the computed root. |
| Compare-and-swap step 4 | In one transaction, insert the seal and advance the tenant head with `UPDATE ... WHERE tenant_id = :tenantId AND root_seq = :observed`. |
| Losing sealer | Zero affected rows means another sealer won. Roll back, re-read, and re-derive from the winning predecessor. The untouched shard heads make this a pure recomputation, never a repair. |
| Dense sequence | `root_seq` is dense and strictly increasing per tenant; gaps, duplicates, and two seals at one position are structurally excluded and verifier-asserted. |
| Deterministic ordering | Close concurrent epochs in canonical `(period, retention_class)` order, one seal per transaction. Ordering is deterministic rather than semantic. |
| Cost | One row lock for one low-frequency update, at most four seals per tenant per month; the 64 hot-path shard chains remain parallel. |
| Failure handling | Count ordinary retries in `audit_epoch_seal_cas_retry_total`; sustained starvation is P2. Preserve a detected fork as a P1 integrity event and never repair it. |

## P1.5 ARC-AUD-006 Verifier Card

| Cadence or assertion | Required verifier behavior |
|---|---|
| Daily, open shards | Walk every open shard chain and verify its links and sequence. |
| Daily, sealed epochs | Validate each sealed epoch against its KMS-signed root without re-walking its retained events, so daily cost follows live volume rather than total history. |
| Quarterly | Fully re-walk every retained epoch. |
| After every restore | Fully re-walk every retained epoch as part of restore verification. |
| Root sequence | Assert `root_seq` is gap-free and duplicate-free for every tenant. |
| Root reproduction | Assert every `epoch_root` reproduces from its recorded predecessor, sequence position, shard heads, and per-shard counts. |
| Failure discipline | A broken link, missing or duplicate sequence, invalid signature, non-reproducing root, or two roots at one sequence is P1. Preserve the evidence and halt; never repair or select a preferred fork. |

`P2.13` designs this scope, `P4.22`-`P4.25` implement it, and
`P7.14`-`P7.15` prove both detection and the absence of a repair path.

## P1.6 ARC-DATA-030 Disposition Card

The four controls make a partition a valid disposition unit without breaking
the proof over retained history.

| Control | Approved rule |
|---|---|
| Physical retention key | Partition `audit_event` by `LIST (retention_class)` then monthly `RANGE (occurred_at)`. Partitions are retention-homogeneous; `shard_id` remains a column, keeping the bound at 4 x 60 = 240. |
| Longest-wins before placement | Resolve the policy at write time, store the winning class and policy version, and express reclassification as a new event rather than an update. |
| Chain boundary equals disposition unit | The epoch is exactly `(retention_class, period)`, so pruning removes a whole chain rather than records from its middle. |
| Anchors outlive data | Seals and checkpoints use the longest retention and survive event disposal, retaining root hash, sequence ranges, per-shard counts, and signature. |

The pruning protocol is ordered and indivisible:

1. Verify every shard link and sequence in the epoch end to end.
2. Recompute the epoch root, match the stored seal, and validate its signature.
3. Emit `AUDIT_EPOCH_DISPOSED` into the current epoch with the disposed epoch,
   sequence ranges, root hash, signature reference, and applied `policy_key`
   plus version.
4. Detach the subpartition, then drop or archive it according to its class.
5. Re-verify every other retained epoch tail for the tenant and the root chain
   across the gap.

A disposition that skips any step is a failed disposition, not a fast one.
This card is the acceptance baseline for `P7.18`-`P7.19`.

## P1.7 ARC-DATA-031 Legal-Hold Card

When an active `platform.legal_hold` covers any row in an audit partition,
the entire partition is promoted to `HOLD_SUSPENDED`. Disposition is suspended
in full, not partially applied, and a suppressed disposition records the hold
reference plus the applied retention-policy key and version. Extracting held
rows would break the chain the hold exists to preserve.

Release resumes the lifecycle from the original retention start event; it
does not reset the retention clock. Non-held rows in the same partition are
therefore retained beyond expiry for the hold's lifetime. Architecture
section 22.1 accepts this as bounded, DPO-visible over-retention: it is
reported in the quarterly compliance audit, and a partition still suspended
after 90 days raises a P3 for Compliance to review whether the hold remains
necessary. This is an explicit trade-off, not a defect.

## P1.8 Audit Grant-Conflict Analysis

The approved baseline conflicts with its audit algorithms. Section 9.2 grants
each `app_<module>` role only `INSERT` on `audit.audit_event`. It grants each
enumerated `app_txn_<flow>` composite role the same insert and explicitly no
`UPDATE` or `DELETE` on `audit.*`. Section 8.3 repeats that the composite role
writes audit on the shared transaction and has no update/delete grant. Those
rows cannot execute the chain-head statement that `ARC-AUD-005` requires.

| Runtime principal | Table | Exact required statement | Required table grant | Baseline result |
|---|---|---|---|---|
| `app_<module>` | `audit.audit_event` | `INSERT` the event on the caller transaction | `INSERT` | Already allowed. `UPDATE` and `DELETE` remain forbidden. |
| `app_<module>` | `audit.audit_chain_head` | `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` as the transaction's last statement | `INSERT`, `UPDATE` | Missing; hot-path audit currently fails. |
| `app_txn_<flow>` | `audit.audit_event` | `INSERT` the event on the shared connection | `INSERT` | Already allowed. `UPDATE` and `DELETE` remain forbidden. |
| `app_txn_<flow>` | `audit.audit_chain_head` | The same `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` as the last statement | `INSERT`, `UPDATE` | Explicit schema-wide no-`UPDATE` wording forbids it. |
| Audit checkpoint writer | `audit.audit_chain_checkpoint` | `INSERT` an immutable signed checkpoint | `INSERT` | Table omitted from section 9.2. No update/delete grant is required. |
| Audit sealer | `audit.audit_chain_seal` | `INSERT` one immutable signed epoch seal in the CAS transaction | `INSERT` | Table omitted from section 9.2. No update/delete grant is required. |
| Audit sealer | `audit.audit_chain_root_head` | `UPDATE ... SET root_seq = root_seq + 1, root_head_hash = :epochRoot, sealed_at = now() WHERE tenant_id = :tenantId AND root_seq = :observed` | `UPDATE`, sealer role only | Table omitted and baseline no-`UPDATE` wording forbids the CAS. |
| Retention role | Expired `audit.audit_event` subpartition | Detach, then drop or archive according to disposition | Partition-scoped retention privilege; no application-role delete | Must stay isolated from application and composite roles. |

`TASK-AUD1-BLOCKER-001` tracks the missing hot-path grants. The ratified
per-table resolution is: `INSERT` only on events, checkpoints, and seals;
`INSERT` plus `UPDATE` on chain heads for module and composite roles; root-head
`UPDATE` only for the sealer role; and expired-partition disposition only for
the retention role. The append-only trigger is scoped to `audit_event`.

`TASK-AUD1-DEFECT-006` records that section 9.2 lists only `audit_event` and
`audit_chain_head`, omitting `audit_chain_checkpoint`, `audit_chain_seal`, and
`audit_chain_root_head`. `P2.5` turns this analysis into the table-level grant
design, and `P3.8` supplies the migrations and sibling assertion amendments.

## P1.9 Attribution Card

| Audit column | Kernel source | Required mapping rule |
|---|---|---|
| `actor_type` | `ActorContext.actorType()` / `ActorType` | Required closed value: `WORKFORCE_USER`, `CANDIDATE`, or `SYSTEM`. |
| `actor_id` | `ActorContext.actorId()` / `ActorId` | Required opaque identifier; email, display name, blank value, and the free-text value `system` are rejected. |
| `system_actor_name` | `ActorContext.systemActorName()` / `SystemActor` | Required and enumerated when `actor_type = SYSTEM`; absent for non-system actors. |
| `tenant_id` | `ActorContext.tenantId()` / `TenantId` | Required for tenant-scoped actions. Null is deliberately permitted only for explicitly authorized platform-scope workforce or system actions. |
| `occurred_at` | `org.meldtech.platform.shared.kernel.time.Clock.now()` | Required server-controlled instant; never client time or an ambient `Instant.now()`. |
| `correlation_id` | `ActorContext.correlationId()` / `CorrelationId` | Required canonical uppercase ULID shared with the request, error, log, and trace context. |

Database non-null/check constraints on the actor identity, controlled time,
and correlation fields turn `BR-IAM-001` and `SC-008` attribution into a
storage invariant rather than a caller convention. The only broad-looking
nullable field is `tenant_id`, and its scope is intentionally narrow:
platform-scope actions only. `P6.10` must enumerate every emission site that
uses that null path so a tenant action cannot escape tenant-scoped review.

## P1.10 Audit Telemetry Card

### Integrity metrics

| Metric | Required dimensions or interpretation |
|---|---|
| `audit_chain_verification_result` | Result of scheduled chain verification. |
| `audit_emit_failure_total` | Count of audit emissions that fail and therefore fail their business transaction. |
| `audit_chain_head_lock_wait_seconds` | p95/p99 over shard waits; a leading indicator for answer-path contention. |
| `audit_chain_shard_skew` | Maximum/median records per shard, showing whether assignment distributes work. |
| `audit_checkpoint_overdue_count` | Open tails that have exceeded checkpoint policy. |
| `audit_epoch_seal_total` | Count by bounded seal result. |
| `audit_epoch_disposition_total` | Count by `DISPOSED`, `HOLD_SUSPENDED`, or `VERIFY_FAILED`. |
| `audit_epoch_seal_cas_retry_total` | Expected CAS losses; sustained growth identifies starvation. |
| `audit_root_chain_fork_detected_total` | Must remain zero; any increment is an integrity incident. |
| `audit_root_chain_seq` | Current tenant root sequence, exposed without an unbounded tenant label. |

`TASK-AUD1-OBS-001` records that the architecture's "by shard" and "by
tenant" wording conflicts with the observability cardinality guard. `P9.2`
must aggregate shard percentiles, use exemplars for investigation, and keep
tenant identity in protected logs/spans rather than metric labels.

### Four primary audit alerts

| Alert | Condition | Severity | First action |
|---|---|---|---|
| Audit emit failure | Any `audit_emit_failure_total` increment | P1 | Treat `SC-008` as at risk because an audit failure would otherwise permit an unattributable action. |
| Audit chain broken | Verification/signature/root reproduction failure, fork counter above zero, a `root_seq` gap, or two seals at one position | P1 | Preserve evidence and escalate possible tampering. Never repair or reconcile a fork. |
| Epoch-seal CAS starvation | Retry rate above 5/min for one tenant or a seal retrying more than 10 times | P2 | Check whether canonical close ordering is being applied; an unsealed epoch blocks disposition. |
| Audit chain-head contention | p95 lock wait above 50 ms for 10 minutes | P2 | Inspect shard skew before answer latency breaches; validate the shard function and tenant `N`. |

Section 16.4 also defines the adjacent operational alerts that `P9.5` carries
to Operations: checkpoint overdue (P2), disposition `VERIFY_FAILED` (P1), and
`HOLD_SUSPENDED` beyond 90 days (P3).

### Dashboard 7 audit panels

Dashboard 7 must show audit-chain verification history; epoch lifecycle with
sealed, disposed, and `HOLD_SUSPENDED` counts and ages; and chain-head lock
wait plus shard-skew trends. Retention dispositions/suppressions and legal
holds remain on the same compliance dashboard.

## P1.11 Verification Ownership

Architecture section 19.8 is authoritative for identifier meanings. This
feature owns only the audit obligations below and does not invent an ID for
an unregistered assertion.

| Verification | Authoritative assertion and gate | Ownership and delivery |
|---|---|---|
| `ARC-VERIFY-010` | Every handler mutating a tenant-scoped aggregate emits at least one audit event in the same transaction; CI stage 4 plus integration. | Owned by `FEAT-AUD-001`. `FEAT-PLAT-001` `P4.25` installed the static R8 rule; audit `P4.15`, `P7.3`, and `P7.4` bind it to the real emitter and prove append-only and positive/negative coverage. |
| `ARC-VERIFY-031` | Worst-case single-tenant audited load plus concurrent multi-class close, dense unforked root sequence, retry-and-re-derive loser behavior, and clean sealer restart. | Owned by `FEAT-AUD-001`; condition A6 and the concurrent-sealing contribution to launch condition `L10`. The harness ships in `P2.19`/`P7.24`; the full-profile discharge remains Phase 6. |
| `ARC-VERIFY-032` | Mixed-retention disposition across monthly boundaries, longest-wins placement, legal hold, retained-proof verification, and a concurrent seal in flight. | Shared by `FEAT-AUD-001` and `FEAT-PRIV-001`; condition A7. Audit owns the executor and integration limbs in `P4.26`-`P4.28` and `P7.17`-`P7.21`; Privacy owns retention execution and policy governance. |
| CI stage 10 audit-payload leak scan | No PIN, OTP, token, or answer key appears in an audit payload during the full end-to-end scan; blocking security release gate. | `FEAT-AUD-001` owns the audit-payload limb in `P3.14`, `P6.5`-`P6.6`, and `P7.25`; `FEAT-SEC-001` consumes the combined cross-surface evidence. This assertion has no dedicated audit `ARC-VERIFY` ID. |

`TASK-AUD1-DEFECT-002` records that plan section 14.4 wrongly describes and
assigns `ARC-VERIFY-011` as audit chain integrity. The real register entry is
"no write path without an `ActorContext`; system actors are enumerated" and
remains owned by `FEAT-PLAT-003`. Daily chain verification is required by
`ARC-AUD-006` and the feature Definition of Done but has no register
identifier; it remains a named CI/daily assertion rather than receiving an
invented one.

## P1.12 Consumer-Contract Table

| Contract produced here | Consumer obligation | Dependant feature | Named delivery or adoption task |
|---|---|---|---|
| Framework-free reactive `AuditEmitter` port whose operation requires an `AuditEvent` and `ActorContext`, joins the caller's existing transaction/connection, and completes only after the event insert and last-statement chain-head advance | Pass every attribution value; subscribe within the business transaction; provide no no-arg, async, listener, new-connection, or `REQUIRES_NEW` path. An emission error must fail the business transaction. | Every state-changing and privileged-read feature; the first concrete existing consumer is the `FEAT-PLAT-004` operator redrive | Port design `P2.2`; implementation `P4.1`, `P4.10`-`P4.14`; publication `P8.1`; `FEAT-PLAT-004` adoption at its `P4.18` |
| Versioned event catalogue `<bounded-context>.<EVENT_CODE>.v<major>`, registered before emission and exactly aligned with the observability `eventCode` token | Declare stable event names and payload classification before implementation; use a new major token for an incompatible semantic change. | Every event-emitting feature; `FEAT-OBS-001` consumes the token alignment | Convention design `P2.3`; publication `P8.1`/`P10.1`; observability alignment at `FEAT-OBS-001` `P2.8` |
| Closed `RetentionClass` enumeration and write-time longest-wins placement with the applied policy version stored on the event | Supply the policy version effective at emission; select the longest applicable class before insert; never compute placement at query time or update an existing event to reclassify it. | `FEAT-PRIV-001` policy/retention engine and `FEAT-CORR-004` result-correction evidence | Placement design `P2.11`; implementation `P4.8`; two-consumer handoff `P8.2` |
| Five-step disposition executor and partition-level hold suspension | Invoke verify, confirm seal/signature, emit disposition evidence, detach, and re-verify in order; treat any skipped/failed step as failed disposition. Suspend an entire held partition and resume from the original clock on release. | `FEAT-PRIV-001` retention execution, with shared `ARC-VERIFY-032` ownership | Protocol design `P2.14`-`P2.15`; implementation `P4.26`-`P4.28`; proof `P7.18`-`P7.21`; handoff `P8.4` |

Only foundation task lists exist in this repository today. The table therefore
names the current producer/handoff tasks and the one already-generated
downstream adoption task (`FEAT-PLAT-004` `P4.18`) instead of fabricating task
identifiers for `FEAT-PRIV-001` or `FEAT-CORR-004`. Their future task lists
must adopt the named `P8.2`/`P8.4` handovers before those features start.
