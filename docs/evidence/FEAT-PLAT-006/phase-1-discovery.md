# FEAT-PLAT-006 Phase 1 Discovery and Analysis

Date assessed: 2026-10-11. Task source: `tasks/foundation/runtime/tasks.md`.
Source plan: sibling repository `student_assessment/workspace/v3/be/plan/plan.md`,
sections 8.0, 8.1, 10, 11.2, 14.4 and 15.1. Architecture references below use
`student_assessment/workspace/v3/be/architecture.md`, v1.4 at `arch-v1.4`,
as pinned by `ci/architecture-ratification.json` and the signed runtime DoR.

This document specifies discovery outcomes, not implementation completion.
Phase 0 dependency evidence is retained in `phase-0-gate-prerequisites.md`.
The role DoR and registry/budget approval verifiers passed again on this date.
The existing untracked Platform Ops provisioning signature was left untouched.

## P1.1 - Scope Coverage

The five scope limbs below transcribe the feature card in plan section 8.1.
Task references refer to this feature unless another owner is named. Mapping
a future task records its purpose; it does not mark that task complete.

| Scope | Plan scope | Principal delivery and verification |
| --- | --- | --- |
| (a) | One image, three profile-selected roles with different enabled beans and identical code, migrations and configuration surface | Role API, manifests and validation (`P2.1-P2.2`, `P4.1-P4.2`, `P4.10`); one image (`P3.10`, `P8.3`); role isolation (`P7.10-P7.13`); telemetry parity (`P4.22`) |
| (b) | Role-appropriate scaling signals | Pool budget (`P1.8`), deployment values (`P3.6`), bulkheads (`P2.9-P2.11`, `P4.18-P4.21`), envelope check (`P8.5`), saturation telemetry (`P9.3`) |
| (c) | The isolated role's distinct workload identity and single-route restriction | Identity and network values (`P3.7-P3.8`), route assertion (`P4.11`), security evidence (`P6.1-P6.8`), isolation test (`P7.13`) |
| (d) | Advisory-lock-based scheduler singletons keyed by sweep name, using PostgreSQL rather than the cache because correctness may not depend on the cache | Registry (`P3.1-P3.4`), lock adapters and runner (`P4.3-P4.9`), contention/recovery tests (`P7.1-P7.8`), relay adoption (`P7.19`) |
| (e) | Graceful shutdown sequencing so in-flight work completes or remains recoverable | Shutdown design/values (`P2.7`, `P3.9`), lifecycle (`P4.14-P4.17`), shutdown tests (`P7.14-P7.16`), operations (`P9.2`, `P9.6`, `P9.10`) |

The following exhaustive allocation covers every numbered task in Phases 0-10,
including prerequisites, discovery, verification, operations and handover.
Multiple limbs indicate a shared obligation. Phase 5 explicitly has no tasks;
appendix checklist entries are acceptance summaries, not additional tasks.

| Task references | Scope supported |
| --- | --- |
| `P0.1-P0.5` | (a), (b), (c): authorization, module/persistence prerequisites and role agreement |
| `P0.6-P0.7` | (b), (d): registry, lock budget and conditional singleton evidence |
| `P0.8` | (c): real workload identity provisioning |
| `P1.1-P1.2` | (a), (b), (c), (d), (e): coverage and control ownership |
| `P1.3` | (d): sweep inventory |
| `P1.4` | (a), (d), (e): failure specification |
| `P1.5` | (d): request-independent security context |
| `P1.6-P1.7` | (a), (c), (d), (e): test gates and startup-verification attribution |
| `P1.8` | (b), (d): pool and permit arithmetic |
| `P1.9` | (a), (b): role telemetry coordination |
| `P2.1-P2.2` | (a): role API and bean matrix |
| `P2.3-P2.6` | (d): registry, keys, lock port and scheduling |
| `P2.7` | (e): shutdown ordering |
| `P2.8` | (a), (c), (e): isolated probes and readiness |
| `P2.9-P2.11` | (b): in-process isolation and bounded resources |
| `P2.12` | (c): reserved route allowlist |
| `P2.13-P2.15` | (a), (d), (e): validators, sweep context and conformance rules |
| `P3.1-P3.5` | (b), (d): registry, grants and budgeted lock pool |
| `P3.6` | (a), (b): workload deployment/scaling values |
| `P3.7-P3.8` | (c): identity and network policy values |
| `P3.9` | (e): shutdown budget |
| `P3.10` | (a): single image |
| `P4.1-P4.2` | (a): role declaration/resolution |
| `P4.3-P4.9` | (d): sweep API, locks, runner, context and registry validation |
| `P4.10` | (a): bean manifest assertion |
| `P4.11` | (c): isolated route assertion |
| `P4.12-P4.13` | (a): worker/API exclusions |
| `P4.14-P4.17` | (e): lifecycle and web shutdown |
| `P4.18-P4.21` | (b): schedulers, concurrency, timeouts and heap |
| `P4.22` | (a), (b): role telemetry |
| `P4.23` | (d): sweep telemetry |
| `P4.24-P4.26` | (a), (d): conformance rules and test-only reference sweep |
| `P6.1-P6.3` | (c): identity, key-policy precondition and network denial |
| `P6.4-P6.8` | (a), (c): database identity, immutable role selection, probes and secrets |
| `P6.9-P6.10` | (d): registry auditing and sweep-log hygiene |
| `P7.1-P7.8` | (d): singleton, loss, contention, adapters and registry failures |
| `P7.9` | (d), (e): interval and shutdown bounds |
| `P7.10-P7.12` | (a): bean manifests and role isolation |
| `P7.13` | (c): reserved route isolation |
| `P7.14-P7.16` | (e): shutdown recovery, release and timing |
| `P7.17-P7.18` | (a), (e): session affinity and stateless failover |
| `P7.19-P7.21` | (d): relay adoption, security context and actor |
| `P7.22` | (b), (d): simultaneous sweep connection budget |
| `P7.23-P7.24` | (a), (b), (c), (d), (e): acceptance and suite evidence |
| `P8.1-P8.2` | (a), (d), (e): blocking conformance/context/integration gates |
| `P8.3` | (a): single signed image/SBOM |
| `P8.4` | (a), (e): deployed affinity and failover drill |
| `P8.5` | (b), (d): connection envelope |
| `P8.6` | (a), (c): isolated-role rollout ordering proposal |
| `P8.7-P8.9` | (a), (d), (e): rollback, registry-before-worker ordering and regression evidence |
| `P9.1` | (d): sweep metrics |
| `P9.2` | (b), (e): shutdown and heap metrics |
| `P9.3` | (a), (b): role identity and saturation |
| `P9.4-P9.5` | (d): staleness and concurrency alert proposals |
| `P9.6` | (e): grace-period alert proposal |
| `P9.7` | (b), (d), (e): dashboard request |
| `P9.8` | (d): stalled-sweep runbook |
| `P9.9` | (a), (c): role-misconfiguration runbook |
| `P9.10` | (e): shutdown runbook |
| `P9.11` | (d): registry-tuning runbook |
| `P9.12` | (a), (d), (e): operational readiness handover |
| `P10.1` | (d): sweep-authoring guide |
| `P10.2` | (a), (c): role-placement guide |
| `P10.3` | (e): shutdown contract |
| `P10.4-P10.6` | (d): registry grant, relay adoption and pooler documentation |
| `P10.7` | (a), (b), (c): role matrix and deployment contract |
| `P10.8-P10.10` | (a), (b), (c), (d), (e): defect acknowledgements, traceability and walkthrough |

No sweep body, PIN-distribution business slice, retrieval-key grant, production
Kubernetes orchestration or full capacity proof is assigned to this feature.
Those remain with the owners listed in the task list's Appendix B.

## P1.2 - Architecture Control Ownership

| Control and defining source | Runtime ownership | Boundary and evidence tasks |
| --- | --- | --- |
| `ARC-PLAT-008`, architecture section 8.2; `ADR-012` | One artifact, closed role selection, role-specific bean sets and PostgreSQL scheduler exclusion | `P2.1-P2.6`, `P4.1-P4.13`, `P7.1-P7.13`; A8/`ARC-VERIFY-033` remains conditional and owned by `FEAT-OPS-005` |
| `ARC-OPS-001`, section 17.1 | One image/SBOM, per-role values and distinct distribution identity mechanism | `P3.6-P3.10`, `P6.1-P6.3`, `P8.3`; Platform Ops/`FEAT-OPS-007` own deployment orchestration, `FEAT-EXAM-002` owns the key grant and A4 denial evidence |
| `ARC-OPS-007`, section 17.6 | Readiness removal, drain ordering, transaction-boundary completion/recovery and lock release | `P2.7`, `P3.9`, `P4.14-P4.17`, `P7.14-P7.16`; capability owners make their work interruptible and idempotently recoverable |
| `ARC-REL-003`, section 14.4 | In-process bulkheads: separate exam/admin schedulers, route concurrency limits, reserved exam pool and per-route-class statement timeouts | `P2.9-P2.11`, `P4.18-P4.21`; consume the persistence feature's pool, with load proof owned by `FEAT-OPS-005` |

`TASK-PLAT6-DEFECT-008`: the feature card omits both `ARC-OPS-007` and
`ARC-REL-003`. Searching the supplied plan finds `ARC-OPS-007` only in
`FEAT-OPS-007`'s reference row (line 3155) and no occurrence of `ARC-REL-003`.
The shutdown scope and bulkhead risk therefore depend on uncited controls.
This feature adopts the defining architecture prose above; `P10.8` owns the
eventual owner acknowledgement and baseline correction.

`TASK-PLAT6-OBS-006`: architecture section 23.7 calls graceful shutdown
`ARC-REL-008`, while the defining section 17.6 calls it `ARC-OPS-007`.
Section 23.7 also calls startup security/policy validation `ARC-OBS-004`, while
section 17.5 defines it under `ARC-OPS-004`. Treat these as aliases for
traceability, not separate implementations or new identifiers. Runtime owns
the role/startup subset; capability-specific policy bounds stay with their
features. The plan cites `ARC-OBS-004` for `FEAT-OPS-004`, and the observability
task list also consumes this alias. Neither transfers every startup validator
to observability.

Verification: defining architecture sections and both matrix aliases checked;
exact-identifier search of the supplied plan confirmed the two omissions.

## P1.3 - Sweep Inventory

Architecture section 8.1 names these six sweeps. System actors come from its
section 8.4 and the existing `shared.kernel.context.SystemActor` enumeration.
The inventory names are descriptive; persisted names and lock keys belong to
`P2.3-P2.4` and `P3.2`.

| Sweep | Owning feature / module | Stated cadence or deadline | Declared system actor |
| --- | --- | --- | --- |
| Auto-submit | `FEAT-DLV-004` / `delivery` | 10 s, section 14.3 `ARC-EXAM-013`; bounded batches and stored deadlines | `AUTO_SUBMIT_SWEEPER` |
| Stale-claim reclaim | `FEAT-GRD-002` / `grading` | 30 s, `ADR-015`; distinct from the configurable stale-claim threshold | `GRADING_WORKER` |
| PIN-material destruction | `FEAT-EXAM-002` / `examaccess` | No numeric sweep interval stated in section 8.1; destruction due no later than entry-window close in the feature card | `RETENTION_ENGINE` |
| Retention | `FEAT-PRIV-001` / capability-owned disposition across modules | No numeric sweep interval stated in section 8.1; retention-policy eligibility, holds and approval govern execution | `RETENTION_ENGINE` |
| IdP reconciliation | `FEAT-IAM-005` / `iam` | No numeric sweep interval stated in section 8.1; feature DoR requires agreement on the maximum reconciliation period | `IDP_RECONCILER` |
| Outbox relay | `FEAT-PLAT-004` / `outbox` | 200 ms, section 11.2 sequence and section 15.3 latency budget | `OUTBOX_RELAY` |

Unspecified intervals remain owner decisions; no arbitrary default is adopted
by this discovery record. Retention's registry `owning_module` must use a real
module from the existing taxonomy when designed, not an invented `privacy`
module or permission to write across schemas. The two retention-related sweeps
share an actor but must retain distinct registry names and lock keys.

No business sweep body is in this task range or this feature's scope. Runtime
supplies registration, role/context checks, scheduling and exclusion. The
existing relay body remains outbox-owned; runtime `P7.19` later adopts it onto
the common lock port. `P4.26` supplies only a test reference sweep.

Verification: all six section 8.1 entries mapped; the three numeric cadences
checked against their defining prose; all five distinct actor names exist in
the current kernel enumeration.

## P1.4 - Failure-Mode Specification

These are required observations for the future Phase 7 suite, not test results.
Outcome labels below are the task-list contract pending instrumentation design.
Every database contention/recovery case must use real PostgreSQL, including
both lock adapters where applicable.

| Failure / injected condition | Required behavior and observable consequence | Verification allocation |
| --- | --- | --- |
| Lock not acquired | Skip immediately; do not enter the batch or wait for the holder; record `skipped-because-lock-not-acquired` and a bounded acquisition result | `P7.1`, `P7.3`, `P7.4` |
| Lock lost mid-tick | Stop further batches, cancel/roll back unsafe in-flight work and record `skipped-because-lock-lost`; a successor must not race unfenced work from the old holder | `P7.2`, both adapters through `P7.4` |
| Replica killed mid-sweep | A live orphaned database session may hold the lock until PostgreSQL detects disconnect; after release another replica proceeds, with committed work retained and unfinished work recoverable | Kill/reacquire case in `P7.2`, recovery assertions in `P7.14-P7.15` |
| Tick slower than its interval | Serialize local execution without overlapping batches or an unbounded tick queue; expose duration/last-success age; the independent relay permit keeps ticking | `P7.5-P7.6` |
| Clock skew between replicas | Exclusion uses database lock ownership, not local wall-clock agreement; stored business deadlines do not move; shifted local clocks must not permit concurrent work | Skew scenario in `P7.1-P7.4`; use the kernel `Clock` |
| Pod evicted during drain | Readiness is removed and new ticks/prefetch stop; completed transactions survive, incomplete units roll back or remain retryable; record any abandoned units | `P7.14-P7.16` |
| Grace period exceeded | Forced termination must leave unfinished work recoverable; incomplete phase evidence/abandonment is visible and cannot be counted as graceful success | Overrun negative case in `P7.14`, timing in `P7.16`, alert proposal `P9.6` |
| Profile unset | Boot fails before accepting work, identifying missing runtime role; no default API role | `P4.2` startup cases in `P7.24` |
| Two runtime profiles set at once | Boot fails and identifies the conflicting roles; no merged bean set | `P4.2` startup cases in `P7.24` |
| Bean joins the wrong role | Conformance or manifest validation fails; API gains no consumer/scheduler, worker gains no business route, pindist gains no extra route | `P7.10-P7.13`, negative rule cases `P4.24` |
| Sweep bean has no registry row | Boot fails naming the sweep; no automatic registration or silently idle runner | `P7.7` |
| Two sweeps share a lock key | Boot fails naming both sweeps; database uniqueness is an additional guard, not a substitute for the boot assertion | `P7.8` |

Lock-based mutual exclusion alone does not establish exactly-once execution
for a globally numbered tick: replicas have independent interval sources and
one can acquire after another releases. `P2.6`/`P7.1` must explicitly reconcile
the card's "exactly one per tick" wording with this fact, including staggered
starts and fast-finishing batches. Durable recovery/idempotency remains the
capability owner's responsibility; clock agreement is not a lock mechanism.

Similarly, loss of a dedicated lock connection does not inherently cancel a
business transaction on a different connection. `P2.5-P2.6` must specify how
loss is detected and how old-holder work is stopped or fenced before claiming
`P7.2` passes. These are design obligations, not claims that PostgreSQL alone
provides a fencing token.

The published 60 s grace period includes the 30 s preStop. A 25 s timeout
*per* lifecycle phase does not bound the sum of multiple sequential phases;
`P2.7` and `P7.16` must budget the full sequence inside the remaining 30 s.

Verification: all twelve task-listed failure modes have a required observation
and a future test allocation; no Phase 7 task or production control is marked
implemented by this analysis.

## P1.5 - Sweep Rule Conformance Without a Request

| Rule | Required background behavior | Existing contract and future proof |
| --- | --- | --- |
| R4 | A scheduler is an infrastructure entry point, not a handler calling another handler. Each bounded business batch uses one transaction; do not wrap a sequence of handlers in a second ambient transaction. Cross-module work uses exposed APIs/events and only the enumerated atomic collaborations where authorized | Architecture section 5.1; `P2.14`, `P4.3`, `P4.7`, `P7.20` |
| R8 | A tenant-scoped mutation still emits its audit event in the same business transaction, using the declared system actor and the affected tenant. Operational tick logs do not replace audit. Runtime's operator registry mutation is audited under `P6.9`, even though the registry itself is non-tenant-scoped | Audit port supplied by `FEAT-AUD-001`; sweep body owned by its capability; `P7.21` checks actor attribution |
| R9 | Install the narrowly permitted database role and enumerated platform-scope marker as the first statements of each platform transaction. No registry query, lock SQL or business SQL precedes initialization. Reset/release on success, rollback, cancellation, timeout and error | Existing `SecurityContextInitializer`; outbox `P2.4` precedent in `docs/architecture/outbox-design.md`; `P4.7`, `P7.20`, `P8.9` |

The proven outbox precedent starts with `SET LOCAL ROLE app_outbox_relay`,
then `SET LOCAL app.platform_scope = 'outbox_relay'`. Its policy uses
`current_setting('app.platform_scope', false)` so absent context fails. The
marker is a closed `shared.api.PlatformOperation`, not a fake tenant UUID,
a free-form string or an RLS bypass. `DatabaseSecurityScopes.withOutboxRelay`
currently exposes that one operation; a generic runtime sweep context has
not yet been implemented.

`app_worker` is the pool login, not blanket business authority. Runtime must
assume a permitted narrow member role before work and cannot introduce
`BYPASSRLS`, disable forced RLS or grant arbitrary cross-schema access. R10
still constrains module/composite role choice, and R2 forbids capability code
from importing `platform.infra`. Expose the declaration via `platform.api`
and keep the initializer/runner adapters internal.

A platform marker alone does not admit tenant-table reads or writes: the
owning module must have an explicit reviewed policy, or the sweep must open
a tenant-scoped transaction for that tenant's batch. The existing initializer
rejects combining tenant and platform scope. A required `ActorContext` carries
the enumerated `SystemActor` and a correlation identifier through Reactor
context; it is not obtained from a request or thread-local fallback.

Plan section 8.0's universal DoD enumerates only R1-R8, whereas architecture
section 5.1 defines R1-R10. This wording gap does not exempt background work
from R9/R10 or the existing conformance tests. The scope of the new runtime
checks complements, rather than replaces, those tests.

Verification: inspected the outbox `P2.4` policy/context design and current
`DatabaseSecurityScopes`/`SecurityContextInitializer`, then compared the
plan's DoD with architecture R4, R8, R9 and R10.

## P1.6 - CI Stage Allocation

`TASK-PLAT6-DEFECT-005`: the feature card names four testing expectations but
assigns none a numbered CI stage or BLOCK gate. Its DoD additionally requires
one image with one SBOM. This allocation uses architecture section 18.1;
pipeline wiring remains `P8.1-P8.4`, not part of this discovery range.

| Expectation | Stage and gate | Required retained evidence / task |
| --- | --- | --- |
| Exactly-one-sweep under N-replica contention | CI 8, BLOCK | Real PostgreSQL, N >= 4, repeated ticks, both adapters, contention/loss/overrun cases; `P7.1-P7.6`, `P8.2`; includes the semantic questions in P1.4 |
| Role isolation and per-role bean enablement | CI 4, BLOCK, plus CI 7 context tests, BLOCK | Static declaration/containment rules and negative fixtures; three manifest-matched contexts; API has no scheduler/consumer, worker no business HTTP, pindist at most its reserved route; `P4.24-P4.25`, `P7.10-P7.13`, `P8.1-P8.2` |
| Session-affinity-off / `ARC-VERIFY-007` | CI 16 / Staging, BLOCK for the applicable obligation | Actually deployed ingress/Service values (`P7.17`), separately labelled surrogate drill (`P7.18`), and scheduled full mid-exam failover (`P8.4`) |
| Graceful shutdown | CI 8, BLOCK | Mid-sweep recovery, lock release/reacquisition and total measured grace-period budget; `P7.14-P7.16`, `P8.2`; scheduled CI 18 drills complement this gate |
| One image with one SBOM (additional DoD) | CI 14 artifact gate; BLOCK on signing failure per the baseline | One immutable signed digest and attached SBOM consumed by all three workloads; `P3.10`, `P8.3`; runtime's one-image assertion must reject role-specific variants |

`ADR-012`'s bean-enablement suite is explicit above even though the baseline
never allocates it a stage. Unit/startup checks remain in the existing test
harness; this record neither changes stage numbers nor invents an
`ARC-VERIFY` identifier for singleton or bean-enablement tests.

The full `ARC-VERIFY-007` scenario kills a replica mid-exam and continues the
attempt elsewhere (architecture sections 17.3 and 19.8). A conformance-slice
surrogate and disabled session affinity cannot discharge that scenario.
`FEAT-DLV-001` and provisioned staging are prerequisites for the Phase 3/6
rerun. Record a pending obligation until its real evidence exists, rather
than treating a missing environment as a passing gate.

Verification: all four named tests and the image/SBOM DoD have stage assignments;
stage numbers and baseline gate types checked against architecture section 18.1.

## P1.7 - Startup Verification Ownership

`TASK-PLAT6-DEFECT-007`: plan section 14.4 assigns `ARC-VERIFY-018` to
`FEAT-PLAT-002`, calls it "row-level security zero rows without predicate",
and gates it at CI 8. Architecture section 19.8 defines that RLS scenario as
`ARC-VERIFY-005`; `ARC-VERIFY-018` instead rejects out-of-range startup
configuration. Section 17.5 supplies its defining `ARC-OPS-004` prose.
This is both a scenario-description and ownership attribution conflict.

| Limb | Accountable owner and evidence |
| --- | --- |
| Runtime role and scheduling startup validation | `FEAT-PLAT-006`: exactly one role, registry completeness, unique keys, bean-manifest equality, route allowlist, sweep interval and shutdown bounds; design `P2.13`, implementation `P4.2`, `P4.8-P4.12`, tests `P7.7-P7.13` and `P7.24` |
| Observability configuration validation | `FEAT-OBS-001`: existing endpoint, sampling, redaction and hash-reference validation; `docs/evidence/FEAT-OBS-001/phase-1-discovery.md` P1.7 and `TASK-OBS1-DEFECT-006` document the same conflict |
| Authorization revocation bounds | `FEAT-IAM-003` and the identity feature contract, including `authorization_revocation_window` |
| Grading retry policy bounds | `FEAT-GRD-002` / `FEAT-GRD-004` capability implementation of the retry-policy contract |
| PIN policy bounds | `FEAT-EXAM-001` and the assessment/PIN-policy owners |
| Production sending approval/configuration | `FEAT-NOTF-004`; separate sending-control evidence, not satisfied by a valid runtime role |
| RLS, grants and pooled database context | `FEAT-PLAT-002`: retain its actual `ARC-VERIFY-005`/`ARC-VERIFY-024` evidence; do not relabel it as runtime startup verification |

Runtime failures must reject boot without clamping, degraded mode or an
override flag. These are the runtime contribution to the existing startup
contract, not proof that every capability validator is complete. No new
register identifier is minted, and this feature does not claim sole ownership
or discharge of `ARC-VERIFY-018`. `P10.8` carries the correction to the
Architecture, persistence and observability owners.

Verification: the contradictory plan row, defining architecture section 17.5,
register section 19.8 and existing observability ownership note were compared.

## P1.8 - Connection Budget

`TASK-PLAT6-DEFECT-003` is resolved by the jointly signed
`ci/dor/FEAT-PLAT-006/P0.6-registry-budget-approval.json`. The consumed figures
come from architecture section 15.2 and the persistence implementation;
runtime adds an explicit lock allocation, not additional connections.

| Workload | Per-replica allocation | Baseline / maximum replicas | Baseline / maximum connections |
| --- | --- | --- | --- |
| `cbt-api` | 14 = 8 reserved exam-path + 6 general | 12 / 30 | 168 / 420 |
| `cbt-worker` | 10 = 4 grading + 6 background | 6 / 24 (10 pre-warmed) | 60 / 240 |
| `cbt-pindist` | 5 | 2 / 2, no HPA | 10 / 10 |

`TASK-PLAT6-OBS-003`: the pindist pool of 5 is stated in section 15.2's
envelope, but omitted from section 17.1's deployment-unit row. The other two
pool figures appear in both sections. Use 5; do not infer another size from
that omission.

The worker's six background connections include **at most two lock holders**:
one permit for serialized sweeps and one independent permit for the relay.
Thus `6 - 2 = 4` remain for background business/dispatcher work, and
`4 grading + 2 locks + 4 remaining = 10`. The lock ceiling is 12 connections
at six worker replicas, 20 at ten pre-warmed replicas and 48 at 24 replicas;
each is already included in the worker totals above.

The current `WorkloadConnectionPoolConfiguration` supplies one worker pool of
10. `P3.5` must partition that allocation when adding the dedicated lock path:
it must not retain a ten-connection business pool and append a two-connection
lock pool. The design must protect grading capacity and count all physical
connections, including a transaction-adapter lock connection while a business
connection is in use. `P7.22` proves the bound; `P8.5` registers it with CI 4a.

| Envelope figure | Baseline | Simultaneous HPA ceilings |
| --- | --- | --- |
| Workload subtotal | 238 | 670 |
| `allocated` (workloads + 20 operations + 10 reserved/internal) | 268 | 700 |
| `allocated_with_headroom` (+ 40 reserved headroom) | 308 | 740 |
| `committed` (+ 10 concurrent migration job) | 318 | 750 |
| `headroom_remaining` (750 - committed) | 432 | 0 |

Pre-warming workers from 6 to 10 adds 40: the corresponding four figures are
308, 348, 358 and 392. At the simultaneous ceiling the 40 reserved-headroom
connections are already included; zero additional `headroom_remaining` does
not mean that reserve disappeared. A third lock permit or larger pool requires
renewed owner approval and a re-derived envelope. A8 remains open; arithmetic
is not the `FEAT-OPS-005` simultaneous-load proof.

Verification: signed budget verifier passed; calculated baseline, pre-warmed
and ceiling totals agree with section 15.2; pool configuration inspected.

## P1.9 - Observability Coordination Record

Record: `TASK-PLAT6-OBS-001`, assessed 2026-10-11. Status: existing published
observability contract adopted; runtime integration scheduled below.

Architecture section 16.1's structured-log table has no `role` field, and
does not define role resource attributes, `service.name` or
`service.instance.id`. The feature card nevertheless requires role identity
on every log, metric and span. This baseline omission is confirmed directly
against section 16.1, not inferred from a missing implementation.

The two features were planned on parallel tracks (plan section 11.2).
`FEAT-OBS-001` has since published a usable counterpart:
`tasks/foundation/observability/tasks.md` **P8.2**, marked complete, with its
role-parity review in `docs/evidence/FEAT-OBS-001/phase-8-deployment-release.md`.
That review requires one `cbt.observability` configuration across all roles.
Its P10.1 contract, `docs/logging-contract.md`, assigns bootstrap role identity
to `FEAT-PLAT-006`. This record accepts that existing ownership split; it does
not claim a new meeting, message or human sign-off.

| Contract surface | Existing name and representation | Shipping owner / runtime obligation |
| --- | --- | --- |
| Structured log field | `role`: `API`, `WORKER`, `PINDIST` | `FEAT-OBS-001` ships the field, encoder and log contract; runtime `P4.22` supplies the validated process role to enrichment on every applicable log path |
| OTLP resource on logs, metrics and spans | `service.runtime.role`: `api`, `worker`, `pindist` | `FEAT-OBS-001` ships the common resource builder and SDK provider wiring; runtime `P4.22` binds/checks it against role resolution |
| Common service identity | `service.name`, sourced from `CBT_OBSERVABILITY_SERVICE_NAME`; environment uses `deployment.environment.name` | Preserve the existing observability configuration contract; do not invent three exporter configurations |
| Bootstrap input | `cbt.observability.resource.role`, sourced from `CBT_OBSERVABILITY_ROLE` | Existing validator checks the closed three-value set; runtime must reject disagreement with the selected role, not merely accept two independently valid values |
| Per-instance identity | `service.instance.id` is absent from the inspected application resource builder and unspecified in section 16.1 | No new attribute is claimed shipped. Any addition for concurrent-sweep diagnosis is an explicit observability contract extension under runtime `P9.3`/`P10.8` |
| Saturation and sweep/shutdown signals | Existing resource role plus the bounded metric contracts to be registered | Runtime `P4.23`, `P9.1-P9.3` own new emitters; `FEAT-OBS-001` owns exporter, schema and cardinality conventions; `FEAT-OPS-004` owns dashboard/alert adoption |

Current evidence is narrower than the eventual acceptance outcome:
`OpenTelemetryTracerConfiguration.resource` installs `service.runtime.role`
on all three SDK providers, while `StructuredJsonLogEncoder` serializes the
role supplied to `StructuredLogEvent`. The nested log role enum already
exists; the future public `platform.api.RuntimeRole` must map to it without
changing the published casing. No comparison to active Spring role profiles
exists in `ObservabilityConfigurationValidator` today. Neither the existing
unit tests nor this record proves role identity on every framework log,
Micrometer/Prometheus series or deployed workload.

Scheduled integration and acceptance:

- `P4.22`: resolve one active role, validate its agreement with the existing
  telemetry input and preserve the established log/resource representations.
- `P9.3`: verify role visibility on every export surface actually used,
  including Micrometer/Prometheus and OTLP; do not confuse the existing
  database `role` metric label with runtime identity or overwrite it.
- `P7.10` and `P7.24`: cover all three roles plus profile/telemetry mismatch;
  `P8.9` consumes the observability P8.2 parity contract during integration.
- `P9.1-P9.7`: register bounded runtime signals and propose missing alerts
  and dashboard panels to `FEAT-OPS-004`; no tenant or correlation-ID labels.
- `P10.8`: obtain the baseline defect acknowledgements; current adoption
  does not pretend those later communications have occurred.

The signed `ci/dor/FEAT-OBS-001/P3.5-collector-contract.json` records the
three-role transport identity contract, not proof that real workload identities
are provisioned. Runtime's P0.8 staging dependency remains in force.

Verification: published logging contract and completed counterpart P8.2
review checked against YAML, encoder, resource-builder and validator code.
Existing pool, encoder, resource and configuration-validator tests passed.
No sibling task markers or production code were changed.

## Execution Summary

- Requested and executed range: `P1.1-P1.9`, in strict sequence. Each task's
  marker was persisted after its evidence was checked, before the next task.
- Completed: `P1.1` scope coverage; `P1.2` control ownership; `P1.3` sweep
  inventory; `P1.4` twelve failure modes; `P1.5` rule conformance; `P1.6` CI
  allocation; `P1.7` startup attribution; `P1.8` connection arithmetic;
  `P1.9` adoption of the existing observability ownership contract.
- Deliverables: this evidence document and nine completion markers plus its
  evidence link in `tasks/foundation/runtime/tasks.md`. No code, configuration,
  migration, external plan or unrelated task was changed.
- Failed or blocked tasks in this range: none. No runtime implementation or
  external approval is claimed by completing these analysis tasks.
- Verification: `./gradlew compileJava compileTestJava --console=plain`
  passed; role DoR and registry/budget approval verifiers passed; scope-table
  validation covered all 133 numbered tasks; arithmetic checked baseline,
  pre-warmed and ceiling figures; documentation-conformance and whitespace
  checks passed.
- Existing targeted tests: `WorkloadConnectionPoolConfigurationTest`,
  `OpenTelemetryTracerConfigurationTest`, `StructuredJsonLogEncoderTest`,
  `ObservabilityConfigurationValidatorTest`: 15 tests, zero failures/errors.
  Full test/build and Docker-backed integration suites were not run for this
  documentation-only range. These tests confirm consumed contracts, not the
  future runtime mechanism.
- Remaining risks: resolve per-tick semantics, lock-loss fencing and total
  shutdown budgeting in Phase 2; preserve the ten-connection worker envelope;
  prove actual role/export parity during integration. Real staging identities,
  mid-exam `ARC-VERIFY-007`, A4 and A8 evidence remain with their recorded
  dependencies and owners.
- Next chunk: `P2.1-P2.15` (architecture and design), using the signed P0.6
  boundaries and the unresolved design obligations above. Phase 2 and later
  tasks remain unstarted in this execution.
