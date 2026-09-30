# FEAT-PLAT-004 Phase 1 Discovery Record

Date: 2026-09-30
Architecture baseline: `arch-v1.4`
Primary sources: plan section 8.1 (`FEAT-PLAT-004`) and architecture
sections 11.2-11.4, 14.4, 14.6, 17.1, and 19.8

This is the working artifact for outbox tasks `P1.1`-`P1.8`. It records
source defects and ownership boundaries without designing the Phase 2
solution or implementing later-phase behavior.

## P1.1 Scope Coverage

The plan defines six scope items. The table maps each item to the task groups
that deliver or verify it; a task may support more than one item.

| Scope | Plan obligation | Delivering and verifying tasks |
|---|---|---|
| (a) | Outbox table and write-in-transaction discipline | `P0.2`-`P0.3`; `P1.3`, `P1.7`-`P1.8`; `P2.1`, `P2.3`-`P2.4`, `P2.8`, `P2.11`, `P2.13`; `P3.1`-`P3.6`, `P3.10`; `P4.1`, `P4.3`, `P4.7`, `P4.9`-`P4.10`, `P4.12`, `P4.17`, `P4.19`; `P6.6`-`P6.9`; `P7.1`-`P7.3`, `P7.7`, `P7.9`-`P7.10`, `P7.18`-`P7.22`; `P8.4`, `P8.7`-`P8.9`; `P9.1`-`P9.2`, `P9.4`-`P9.7`, `P9.9`-`P9.10`; `P10.3`-`P10.5`, `P10.7`, `P10.10`-`P10.12` |
| (b) | Relay from outbox to broker with at-least-once delivery and the adopted no-ordering contract | `P0.5`-`P0.7`; `P1.2`, `P1.4`, `P1.7`-`P1.8`; `P2.2`-`P2.7`, `P2.11`-`P2.13`, `P2.15`; `P3.2`-`P3.8`, `P3.10`; `P4.4`-`P4.9`, `P4.11`-`P4.12`, `P4.18`-`P4.19`; `P5.1`; `P6.1`-`P6.3`, `P6.6`-`P6.9`; `P7.2`, `P7.4`-`P7.7`, `P7.10`-`P7.13`, `P7.17`-`P7.20`; `P8.1`, `P8.4`-`P8.9`; `P9.1`-`P9.10`; `P10.2`, `P10.5`, `P10.7`-`P10.12` |
| (c) | Consumer idempotency on stable business identity | `P0.4`; `P1.3`, `P1.7`; `P2.7`-`P2.8`, `P2.15`; `P3.8`-`P3.9`; `P4.10`-`P4.12`, `P4.17`-`P4.19`; `P5.1`; `P7.3`, `P7.7`-`P7.8`, `P7.13`, `P7.21`-`P7.22`; `P8.1`, `P8.9`; `P10.2`-`P10.4`, `P10.10`-`P10.12` |
| (d) | Versioned event contracts and compatibility against a registered baseline | `P0.4`; `P1.5`-`P1.6`; `P2.7`, `P2.9`-`P2.10`, `P2.12`; `P3.8`-`P3.9`; `P4.2`-`P4.3`, `P4.11`-`P4.14`, `P4.17`; `P6.4`-`P6.5`, `P6.9`; `P7.13`-`P7.15`, `P7.21`-`P7.22`; `P8.1`-`P8.2`; `P9.3`, `P9.5`, `P9.8`; `P10.1`-`P10.4`, `P10.9`-`P10.12` |
| (e) | Retry, dead-letter handling, and visible backlog | `P0.5`-`P0.7`; `P1.2`, `P1.4`, `P1.7`-`P1.8`; `P2.2`, `P2.5`-`P2.7`, `P2.11`-`P2.13`, `P2.15`; `P3.2`-`P3.8`, `P3.10`; `P4.4`-`P4.8`, `P4.11`, `P4.18`-`P4.19`; `P5.1`; `P6.1`-`P6.3`, `P6.7`-`P6.8`; `P7.2`, `P7.4`-`P7.7`, `P7.11`-`P7.13`, `P7.17`-`P7.20`, `P7.22`; `P8.1`, `P8.4`-`P8.8`; `P9.1`-`P9.10`; `P10.2`, `P10.5`, `P10.7`-`P10.12` |
| (f) | `ADR-023` synchronous atomic collaboration is the sole enumerated exception | `P0.1`, `P0.5`; `P1.1`, `P1.5`; `P2.3`-`P2.4`, `P2.14`; `P3.3`-`P3.5`; `P4.9`, `P4.15`-`P4.16`; `P6.6`-`P6.7`; `P7.9`-`P7.11`, `P7.16`, `P7.22`; `P8.3`, `P8.9`; `P10.6`-`P10.7`, `P10.10`-`P10.12` |

The reverse coverage check below accounts for every task identifier in the
task list, including governance and handover work that spans all six items.

| Task range | Scope coverage |
|---|---|
| `P0.1`-`P0.7` | (a)-(f), as allocated above; these are authorization and dependency gates |
| `P1.1`-`P1.8` | (a)-(f); discovery establishes coverage, ownership, contracts, failure cases, and capacity |
| `P2.1`-`P2.15` | (a)-(f); table, relay, delivery, contract, security, retention, and redrive designs |
| `P3.1`-`P3.10` | (a)-(f); PostgreSQL, RabbitMQ, contract baseline, and runtime configuration infrastructure |
| `P4.1`-`P4.19` | (a)-(f); writer, relay, consumer, compatibility, conformance, redrive, and instrumentation |
| `P5.1` | (b), (c), (e); operator surfaces consume query and redrive capabilities supplied here |
| `P6.1`-`P6.9` | (a), (b), (d), (e), (f); credentials, transport, least privilege, and payload hygiene |
| `P7.1`-`P7.22` | (a)-(f); behavioral, security, compatibility, capacity, and acceptance proofs |
| `P8.1`-`P8.9` | (a)-(f); CI gates, role wiring, deployment ordering, rollback, and regression evidence |
| `P9.1`-`P9.10` | (a), (b), (d), (e); metrics, alerts, trace carriage, runbooks, and readiness |
| `P10.1`-`P10.12` | (a)-(f); normative contracts, guides, defect records, traceability, and walkthrough |

Result: every scope item has delivery and verification tasks, and every task
in `P0.1` through `P10.12` maps to at least one scope item. The plan's
ordered-per-key statement is not silently retained: the approved task list
adopts an order-insensitive, version-guarded consumer contract under
`TASK-PLAT4-DEFECT-006`, to be designed in `P2.7`.

## P1.2 ARC-REL Ownership

The feature card cites `ARC-REL-001...008` as a block, but this feature does
not own that whole block. Ownership follows the behavior each decision
governs.

| Decision | Architectural behavior | Owning feature | FEAT-PLAT-004 relationship |
|---|---|---|---|
| `ARC-REL-001` | Candidate path depends on PostgreSQL only | `FEAT-OPS-003` | Constraint consumed: broker publication must remain off the request path |
| `ARC-REL-002` | Proctor-session predicate avoids an IdP round trip | `FEAT-IAM-001` | No implementation ownership |
| `ARC-REL-003` | In-process bulkheads, reserved pool capacity, and timeouts | `FEAT-PLAT-006` | Consumes the background-role and six-connection shared budget; see `P1.8` |
| `ARC-REL-004` | Bounded retry with jittered backoff | Split by integration owner | Owns the outbox-publication limb in `P2.6`, `P4.5`, and `P7.12`; database reconnect remains outside this feature |
| `ARC-REL-005` | Redis degrades to PostgreSQL without candidate-path denial | `FEAT-PLAT-003` | No implementation ownership |
| `ARC-REL-006` | Broker outage becomes a monitored backlog rather than data loss | `FEAT-PLAT-004` | Fully owned through durable `PENDING` rows, recovery drain, metrics, alerts, and tests |
| `ARC-REL-007` | Stored deadlines prevent replica clock skew changing an attempt | `FEAT-DLV-001` | No implementation ownership |
| `ARC-REL-008` | Graceful shutdown sequencing for in-flight submissions and workers | `FEAT-PLAT-006` | Relay participates in worker drain but does not own workload shutdown policy |

Therefore this feature owns `ARC-REL-006` and the outbox-publication limb of
`ARC-REL-004`. It supplies evidence to `ARC-REL-001`, `ARC-REL-003`, and
`ARC-REL-008` without taking ownership from their named features.

## P1.3 Idempotency Inventory

The machine-readable inventory is published at
`contracts/idempotency-inventory.yaml`. It contains all 13 architecture
section 14.6 operations with durable key, store, owning feature, and proof
status. Only `outbox-publication` is owned by `FEAT-PLAT-004`; its planned
proof is `P7.3`. The already-delivered generic POST convention names its
existing `FEAT-PLAT-003` tests, while proofs owned by future features remain
explicitly pending rather than being guessed here.

## P1.4 Queue-Topology Ownership

Architecture section 11.4 defines queue behavior but does not transfer
business-lane ownership to the outbox feature. Declaration ownership is:

| Topology | Required properties | Declaration owner | Boundary |
|---|---|---|---|
| `integration` exchange | Durable event exchange used by the generic integration topology | `FEAT-PLAT-004` | This feature declares the exchange and routing conventions, not business event types |
| `integration.<context>` | Durable quorum queue, prefetch 32, dead-letter route to `integration.dlq` | `FEAT-PLAT-004` | This feature declares the generic per-context queues; the context owner supplies its consumer and handled-version declaration |
| `integration.dlq` | Durable dead-letter destination with a distinguishable unhandled-version reason | `FEAT-PLAT-004` | This feature supplies dead-letter routing and redrive mechanics |
| `grading.priority` | Quorum priority queue, prefetch 5, dead-letter to `grading.dlq` | `FEAT-GRD-003` | Priority-lane capacity and routing are grading concerns |
| `grading.standard` | Quorum queue, prefetch 16, dead-letter to `grading.dlq` | `FEAT-GRD-002` | Standard grading consumption follows the grading lifecycle |
| `grading.retry.{2s,8s,32s}` and `grading.dlq` | Quorum TTL retry queues and grading dead-letter handling | `FEAT-GRD-004` | Retry classification and escalation remain with grading recovery |
| `notification.pin`, `notification.otp`, `notification.general`, and `notification.dlq` | Quorum queues, stream-specific prefetch, credential-sensitive alerting | `FEAT-NOTF-001` | Notification dispatch owns its isolation, consumer, and DLQ policy |

`FEAT-PLAT-004` therefore supplies only the generic `integration.*` substrate.
It neither declares nor configures `grading.*` or `notification.*`, and it
does not absorb their retry, concurrency, or provider semantics.

## P1.5 Verification Ownership Correction

Tracking: `TASK-PLAT4-DEFECT-005`.

Plan section 14.4 assigns `ARC-VERIFY-012`, `013`, and `014` to outbox,
event compatibility, and the idempotency inventory. Architecture section
19.8 is the identifier authority and defines those scenarios differently.

| Identifier or gap | Authoritative architecture meaning | Correct owner | FEAT-PLAT-004 relationship |
|---|---|---|---|
| `ARC-VERIFY-006` | Composite-role grants match `ADR-023`; asynchronous cross-module propagation is outbox-only | Split between `FEAT-PLAT-001` and `FEAT-PLAT-004` | This feature owns the database/integration limb; `FEAT-PLAT-001` task `P4.24` owns the static R7 limb |
| `ARC-VERIFY-012` | OpenAPI has no unversioned breaking change | API contract owner; existing plan assignment is incorrect | No outbox ownership |
| `ARC-VERIFY-013` | Fault injection yields only allowlisted `ProblemDetail` responses with correlation identifiers | `FEAT-PLAT-003` | No outbox ownership |
| `ARC-VERIFY-014` | All five provider-webhook controls are necessary and replay is a no-op | `FEAT-NOTF-003` | No outbox ownership |
| No registered identifier | Event-schema compatibility | Gap to be raised to the Architecture Owner | Still implemented as a blocking CI stage 9 contract gate; no identifier is invented |
| No registered identifier | Section 14.6 idempotency-inventory coverage | Gap to be raised to the Architecture Owner | Still implemented as the `P4.17` manifest gate; no identifier is invented |

The plan attribution is not used as authority. This record adopts
`ARC-VERIFY-006`'s integration limb, preserves baseline task `P4.24` as its
static counterpart, and leaves both register gaps explicitly unnamed until
the Architecture Owner revises the baseline.

## P1.6 Prospective Event Register

This sizing register follows the eight named downstream dependencies in the
task-list overview. It transcribes only what their plan cards state. Names,
payloads, schemas, and versions remain the publishing feature's design work.

| Downstream feature | Plan-card event or mechanism need | Contract owner | Register interpretation |
|---|---|---|---|
| `FEAT-OBS-001` | Correlation and trace context must cross every outbox and broker boundary | `FEAT-OBS-001` owns telemetry semantics; each publisher owns its event | Envelope metadata is required; this is not a new business event |
| `FEAT-TENANT-001` | Publishes `TenantCreated` for default provisioning and administrator notification | `FEAT-TENANT-001` | One producer contract plus consumers in provisioning and notification |
| `FEAT-GRD-002` | Consumes `AttemptSubmitted`; publishes `AttemptGraded` to `result` | `FEAT-DLV-001` owns `AttemptSubmitted`; `FEAT-GRD-002` owns `AttemptGraded` | Two versioned contracts and both producer/consumer compatibility paths |
| `FEAT-NOTF-001` | Consumes signals for tenant onboarding, candidate registration, PIN issuance, exam scheduling, result publication, and result correction | The corresponding business feature owns each event; `FEAT-NOTF-001` owns consumption | At least six handled business-event families; no notification-owned copies of those contracts |
| `FEAT-DLV-002` | Answer acceptance is synchronous and exposes no integration event | `FEAT-DLV-002` owns the answer operation; `FEAT-PLAT-004` owns the negative conformance rule | Explicit zero-event hot path under `ADR-009`; `P4.16` prevents an outbox insert |
| `FEAT-RSLT-002` | Publishes `ResultPublished` to drive notification | `FEAT-RSLT-002` | One producer contract, also referenced by notification consumption |
| `FEAT-CORR-003` | Publishes a result-republication event to drive notification | `FEAT-CORR-003` | One producer contract; the plan does not assign its final type name here |
| `FEAT-OPS-003` | Backup, restore, and durability cover the outbox as PostgreSQL data; no business event is named | `FEAT-OPS-003` owns recovery; event owners remain unchanged | Infrastructure dependency only, with no contract invented |

For mechanism sizing, the cards therefore require generic versioned schema
registration, multiple publishers and consumers, fan-out to notification,
trace headers on every message, and support for explicitly event-free paths.
This register is non-normative and does not authorize a payload or final type
name; those remain with the feature in the `Contract owner` column.

## P1.7 Failure-Mode Table

| Failure point | Observable consequence | Required survival behavior and proof |
|---|---|---|
| Process crashes before the business transaction commits | Neither the business change nor its outbox row is visible; transaction rollback is observable in PostgreSQL | No phantom event may be published. `P7.1` and `P7.2` prove the negative direction |
| Process crashes after commit but before publish | The business change and one `PENDING` outbox row remain; backlog depth and oldest age increase | A later relay tick publishes the durable row. `P7.1`, `P7.4`, and the backlog metrics prove recovery without request-path coupling |
| Relay crashes mid-batch | Some rows may be `PUBLISHED`; unconfirmed rows remain `CLAIMED` until `claim_expires_at`; relay liveness and stale-claim metrics expose the interruption | Expired claims return to `PENDING`; already accepted messages may be delivered again. `P7.7` proves no row remains stranded and consumers absorb duplicates |
| Broker is unavailable | Publisher confirms fail; `PENDING` depth and oldest age rise while request transactions continue to commit | Bounded retry must not occupy the request path; recovery drains oldest-first. `P7.4` proves no loss, no duplicate effect, and unaffected request handling |
| Broker accepts a message, then the relay crashes before marking the row `PUBLISHED` | Broker has a deliverable message while PostgreSQL still has `CLAIMED`, later reclaimed to `PENDING`; the same event may be published twice | At-least-once delivery is explicit. Stable `outbox_event_id` plus the consumer business key make the second delivery a no-op; `P7.3` and `P7.7` prove it |
| Consumer crashes after applying the effect but before broker acknowledgement | If the consumer transaction committed, both effect and `processed_event` guard exist and the broker redelivers; if it did not commit, neither exists | Effect and dedupe guard share one transaction. Redelivery either completes the missing effect or observes the guard and acknowledges without reapplying; `P7.3` proves both |
| Broker delivers the same event more than once | Delivery count exceeds effect count; the second transaction finds the event guard or business key | Exactly one business effect is observable despite multiple deliveries. `P7.3` is the end-to-end proof |
| Broker delivers events out of causal order | Arrival order differs from aggregate-version order; a stale event reaches the consumer after a later version | Consumers rely on no ordering guarantee and apply a version guard so stale state cannot overwrite newer state. `P7.8` proves the correct final state |
| Publisher or consumer encounters a poison payload | Publication failures increment `attempt_count`; at 8 the row is `FAILED`. Consumer poison enters the relevant DLQ. Metrics and distinct alerts expose both paths | A poison item never blocks following work. Operator inspection and audited redrive are required; `P7.12` proves publication failure and `P7.13` distinguishes an unhandled version from poison |

These outcomes define observable invariants rather than implementation
exceptions. A recovery path is incomplete unless the durable state, metric or
DLQ consequence above can be asserted by its named Phase 7 test.

## P1.8 Worker Connection Budget

Architecture section 17.1 fixes each `cbt-worker` pool at 10 connections:
four for the bounded grading executor and six shared by the outbox relay,
sweepers, and notification dispatcher. The repository matches that baseline
with `cbt.database.pools.worker.max-size: 10` in `application.yaml`.

| Use | Steady-state connection ceiling per worker instance | Budget consequence |
|---|---:|---|
| Grading executor | 4 | Fixed by executor concurrency; not available to background coordination |
| Outbox relay | 1 | One active claim/state transaction at a time; sequential publication must not create parallel database work |
| Sweepers and notification dispatcher | 5 remaining | Shared capacity retained for their bounded work |
| Total | 10 | Does not exceed the configured pool |

The relay uses no dedicated second pool and may not hold more than one
database connection concurrently in steady state. Advisory-lock contenders
may each borrow one shared connection for a tick, but still remain within the
same per-instance allocation. Broker connections are not PostgreSQL pool
connections and do not alter this arithmetic.

No manifest or pool-size increase is introduced by this feature, so the CI
stage 4a envelope remains unchanged: baseline and HPA calculations continue
to use `cbt-worker replicas x 10`. Task `P7.20` measures writer cost and task
`P8.4` registers the relay use against that existing envelope; either must
fail if implementation exceeds the one-connection relay ceiling.
