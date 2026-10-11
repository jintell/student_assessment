# FEAT-PLAT-006 Phase 2 Architecture and Design

Date: 2026-10-11. Range: `P2.1-P2.15` in
`tasks/foundation/runtime/tasks.md`. Source plan:
`../student_assessment/workspace/v3/be/plan/plan.md`, sections 8.0, 8.1,
10, 11.2, 14.4 and 15.1. Architecture: v1.4, tag `arch-v1.4`, as pinned
by the signed runtime DoR and registry/budget approval under
`ci/dor/FEAT-PLAT-006/`.

This record specifies future implementation. It adds no runtime behavior,
migration, business endpoint or sweep body. Phase 0 and Phase 1 prerequisites
are complete; both `verify-runtime-role-dor` and
`verify-runtime-registry-budget-approval` passed on this date. The signed
approval permits an additive registry and two lock permits within the existing
worker allocation, not a new connection allowance. Phase 3 onward implements
and tests the decisions below. Numeric recommendations are explicitly proposals,
not new architecture approvals or claims that A4/A8 are discharged.

## P2.1 - Role Declarations

Publish `RuntimeRole` and `RoleScoped` in `platform.api`; expose that existing
module API through the current Modulith boundary. `RuntimeRole` has exactly
`API("api")`, `WORKER("worker")`, `PINDIST("pindist")` and a strict
profile-name lookup. There is no default runtime role and no migration role.
`cbt-migrate` retains its separate bootstrap and does not enter this resolver.

Declaration shape for P4.1 (not production code in this phase):

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile({})
public @interface RoleScoped {
    @AliasFor(annotation = Profile.class, attribute = "value")
    String[] value();
}
```

Examples are `@RoleScoped("worker")` and `@RoleScoped({"api", "pindist"})`.
The string array is intentional: Spring's `Profile.value` is `String[]`, and
an explicit alias must have the same return type. An enum array cannot alias
it. The enum remains the closed runtime vocabulary; conformance and startup
metadata validation reject empty/duplicate values, unknown names, expressions,
negation and placeholders. Ordinary environment profiles such as `production`
may coexist with exactly one role, but cannot appear in `RoleScoped`.
See the Spring [Profile contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/Profile.html)
and [alias requirements](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/annotation/AliasFor.html).

Use one direct declaration on the component type, or on the uniquely named
factory method for a factory-created bean. Never both; no inherited,
repeatable or custom composed role declarations. An enclosing configuration
does not substitute for an endpoint/consumer/sweep's own declaration. Shared
supporting adapters may be role-neutral; executable entry points may not.
Bare `@Profile("worker")` is not a role declaration. Additional environment
conditions must not widen the role membership.

The resolver and metadata validator live in `platform.infra.runtime`, remain
package-private where Spring permits, and freeze the resolved role before
bean instantiation. Scan candidate metadata including profile-excluded types,
then count resolved active role profiles after profile groups/includes are
expanded. Reject zero or multiple roles before external listeners start.
`RoleScoped` has no reference into infrastructure. Domain types cannot use it:
R6 forbids framework-bearing declarations there, and the role rule explicitly
rejects `..domain..` usage. No shared-kernel type is added.

Verification: checked API placement against R2/R6 and existing `platform.api`;
checked Spring alias types and OR profile semantics. P4.1/P4.2 and P7.10-P7.12
must cover all three roles, extra environment profiles, missing/multiple roles,
factory methods, invalid annotation values and excluded-bean metadata.

## P2.2 - Golden Role Manifests

The three committed JSON documents in `contracts/runtime-roles/` are the
golden role policy and initial entry-point inventory. `DESIGN_BASELINE` means
they describe intended restrictions, not successful context isolation. They
transcribe architecture section 8.2 and the signed P0.4 agreement: API serves
ordinary slices; worker runs consumers/sweeps and no business HTTP; pindist
serves only the reserved read with its own identity and no schema. Supporting
security, persistence, telemetry and health beans are required by all roles;
"slice endpoints only" describes entry points, not every support bean.

The existing reference endpoint is the sole listed concrete API entry point.
Worker and pindist inventories are empty because neither the scheduled
reference bean nor distribution slice is delivered yet. Outbox helper classes
are not invented consumer or sweep beans. Capability names describe future
owners' contributions. Health is a separately classified management surface.
The versioned reserved path follows P2.12 and carries TASK-PLAT6-OBS-002.

Manifest format: role/workload are unique; category lists are disjoint closed
sets; `entryPoints` contains sorted, unique user-class names and kind plus
route method/template/ID or consumer queue/sweep name as applicable. Factory
beans additionally carry declaring class and method, never a generated proxy
name. `businessRoutes` is a policy restriction independent of current entries.
Unknown fields/categories, duplicate JSON keys, repeated identities or routes
are errors. A future format revision must increment `schemaVersion`.

Generation and comparison procedure for P4.10/P7.10-P7.13:

1. Compile the same artifact; build a candidate entry-point catalogue from all
   production definitions and factory metadata, including excluded roles.
2. Boot three isolated test contexts with exactly one role and controlled
   dependency fixtures. Validate declaration coverage before starting listeners.
3. Enumerate unproxied entry points, listener containers, sweep definitions and
   actual WebFlux mappings. Compare declared metadata with registered routes;
   examine functional routers as well as annotated handlers.
4. Emit deterministic candidate JSON under `build/`, sorted by kind/type/route.
   Compare exact sets with the committed manifests and enforce category/path
   restrictions even when snapshots happen to match. Missing and extra entries
   both fail. Infrastructure beans are excluded by explicit classification,
   not by ignoring an entire package or unrecognised bean type.
5. Review diffs in the owning feature change, then deliberately update each
   affected golden file. Verification never rewrites expected data. On adopting
   the generator, replace `DESIGN_BASELINE` with `VERIFIED_INVENTORY` only after
   the context suites pass. A design baseline cannot satisfy the release gate.

The reference sweep is added through a test-only manifest overlay; it must
never appear in the production artifact. When FEAT-EXAM-003 becomes delivered
in `contracts/feature-delivery-status.yaml`, pindist requires exactly the
reserved route. Until then it serves probes only, as P0.4 explicitly permits.

Verification: compared all role policies with section 8.2/P0.4, inspected the
reference endpoint's route descriptor, and parsed all three manifests as JSON.

## P2.3 - Registry Table

`platform.sweep_registry` is an additive, non-tenant-scoped control table,
owned by `app_migrator` and delivered by runtime P3.1-P3.4. This uses the exact
scope approved for TASK-PLAT6-DEFECT-002 in the signed P0.6 record. Register it
explicitly in `docs/tenant-scoped-tables.md` and the executable persistence
catalogue; it has no `tenant_id`, RLS tenant predicate or silent RLS exemption.

| Column | Proposed SQL contract | Property served |
| --- | --- | --- |
| `sweep_name` | `varchar(96) PRIMARY KEY`, canonical lowercase ASCII segments separated by hyphens | Stable identity independent of Java class/refactor |
| `lock_key` | `bigint NOT NULL UNIQUE` | Persisted 64-bit lock identity; collision rejection |
| `owning_module` | `varchar(32) NOT NULL`, checked against the module catalogue at boot | Module ownership and narrow security policy selection |
| `owning_feature` | `varchar(32) NOT NULL`, valid feature ID | Delivery owner and enabling evidence |
| `default_interval_ms` | `bigint NOT NULL CHECK (> 0)` | Durable default; bounded effective value validated at boot |
| `enabled` | `boolean NOT NULL DEFAULT false` | Explicit opt-in when the owning capability is delivered |
| `system_actor` | `varchar(40) NOT NULL`, validated against `SystemActor` | Enumerated identity, also matched to the bean |
| `created_at` | `timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP` | Immutable creation evidence |
| `updated_at` | `timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP`, `CHECK (updated_at >= created_at)` | Most recent controlled configuration mutation |

The name grammar is `[a-z][a-z0-9]*(?:-[a-z0-9]+)*`; length is bounded by the
column. Disallow normalization on read: renaming means an explicitly reviewed
migration, never recomputing the key. No additional heartbeat, lease, payload,
last-run, tenant or credential columns are introduced. Metrics track outcomes.
Application timestamps use the injected kernel Clock; migration defaults use
database time. A controlled update explicitly sets `updated_at`.

P3.2 reserves `auto-submit`, `stale-claim-reclaim`, `pin-material-destruction`,
`retention`, `idp-reconciliation` and `outbox-relay`, matching the P1.3 owners
and actors. Their module names are respectively `delivery`, `grading`,
`examaccess`, `audit` (disposition coordination), `iam`, and `outbox`.
Retention's owner must confirm that coordination assignment before its row is
enabled; no new retention module is implied. Stated intervals are 10000, 30000
and 200 ms for auto-submit, stale reclaim and relay. For the other disabled
rows, propose 60000 ms as a seed placeholder, explicitly requiring the owning
feature's cadence/deadline decision before enablement. All six start disabled;
P4.26 supplies the enabled reference row through test fixtures only.

Only `app_worker` receives registry SELECT. No API/pindist grants, new database
role or runtime DDL is needed. Operator updates remain a future controlled
platform operation under P6.9, with authorization and an audit append in the
same transaction. Runtime login roles receive no generic registry UPDATE.
Reload is performed at a tick boundary after validating a complete snapshot;
a missing/invalid row prevents further starts and makes the worker unready.
Disabling a sweep prevents new ticks and allows the current batch to finish;
identity/key changes require a stopped rollout, not a hot reload.

Verification: all nine approved columns have an invariant or ownership purpose;
checked existing platform schema ownership, workload login roles, registry grant
scope and the Phase 1 sweep inventory. Actual SQL, catalogue edits and grant
tests remain P3.1-P3.4; no migration is authored in Phase 2.

## P2.4 - Stable Lock Keys

Use the existing relay's FNV-1a 64-bit derivation once, at migration preparation:
start with unsigned `0xcbf29ce484222325`; for each UTF-8 byte of the canonical
sweep name, XOR the unsigned byte then multiply by `0x100000001b3` modulo
2^64. Interpret the final bits as a signed two's-complement `bigint`. There is
no salt, locale conversion, Java `hashCode`, absolute-value step or runtime
rehashing. FNV is an identity derivation, not a cryptographic control.

| Canonical name | Stored signed key |
| --- | --- |
| `auto-submit` | `8663753269692232313` |
| `stale-claim-reclaim` | `-6495990722266513791` |
| `pin-material-destruction` | `-2916676166989647429` |
| `retention` | `-4505843845379211855` |
| `idp-reconciliation` | `-4891396382355818010` |
| `outbox-relay` | `-2484966499978243116` |

The migration generator checks uniqueness across every existing and proposed
registry entry, including disabled rows. PostgreSQL's unique constraint is the
second guard. Boot loads the complete registry and independently rejects any
duplicate keys, names or inconsistent bean identities before scheduling. This
also covers a restored database whose constraint was damaged or removed. A
collision is an explicit generation/boot failure; never silently probe for a
different key. A reviewed replacement name can resolve a pre-release collision.

Acquire by looking up the immutable registry snapshot's stored key. Configuration
cannot override a key. Retuning an interval cannot change it. A relay rolling
upgrade uses the same `outbox-relay` name/key as the old helper so session and
transaction lock contenders share the same PostgreSQL bigint lock space.
Inventory any deployed historical names before adoption; a rename requires
stopping old runners before the new name is enabled. Remove the relay-local
derivation/acquisition at P7.19, as mapped in Phase 1 (the relay comment's
P7.17 reference is stale). Test-only names get registry fixture rows.

Verification: independently calculated the six vectors above and asserted
uniqueness; compared byte operations with `RelaySingleton.lockKey`. Future
tests cover signed conversion, duplicate seeded keys, a tampered registry,
unknown names and preserved relay contention during adapter adoption.

## P2.5 - Reactive Lock Port and Adapters

Publish only the port and opaque handle contract in `platform.api`:

```java
interface SweepLock {
    Mono<Handle> tryAcquire(String sweepName);

    interface Handle {
        Mono<Void> assertHeld();
        Mono<Void> close();
    }
}
```

These are public contracts when implemented. The handle is asynchronously
released-on-close, not Java `AutoCloseable` with a blocking void method.
The runner owns it through `Mono.usingWhen` with complete/error/cancel cleanup;
no `block()`, fire-and-forget cleanup subscription or async work in a Java
`finally`. Empty acquisition means contention; a missing row, bad configuration,
pool timeout or database failure is an error, never a false contention result.
The adapter owns the checked-out connection from acquisition until cleanup.

| Adapter in `platform.infra.runtime` | Acquire and retain | Release |
| --- | --- | --- |
| `SessionSweepLock` (default) | One dedicated connection; parameter-bound `pg_try_advisory_lock(bigint)`; retain that physical session for the entire tick | `pg_advisory_unlock` on that same connection, verify true, reset then return to pool |
| `TransactionSweepLock` | Begin a dedicated lock-only transaction; parameter-bound `pg_try_advisory_xact_lock(bigint)`; keep the transaction and backend pinned across the tick | Roll back the lock-only transaction on every terminal path, then return to pool |

The port is the sole sweep advisory-lock acquisition surface. P7.19 removes
`RelaySingleton`'s direct SQL; migrations must not add a competing acquisition
mechanism. Select one adapter process-wide using a closed `session` / `transaction`
property; reject `session` with declared PgBouncer transaction pooling. Both
use the same stored key and permit accounting. PostgreSQL
[documents the two lock lifetimes](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS),
and PgBouncer [does not support session locks in transaction pooling](https://www.pgbouncer.org/features.html).

On false acquisition, end the attempted transaction if applicable and close
immediately. Bound checkout and query time; do not wait for a lock or retry in
the same tick. `close()` is idempotent. Cancellation racing an acquisition
must still drain/resolve the result and unlock, or invalidate the physical
connection; logical pool return alone is unsafe for a session lock. Any unlock,
reset, rollback or ownership-check failure invalidates the physical connection
instead of returning an uncertain lease. Count it as infrastructure failure.

`assertHeld()` checks connection/transaction health and the owned bigint lock
on the retained backend, without reacquiring the reentrant lock. Serialize
these checks with close and other commands on that connection. The runner
checks before each business batch and after each batch; network/read timeouts
bound the check. Loss cancels further work, records `skipped-because-lock-lost`
and prevents the next batch. Business batches use their own security-scoped
transactions, bounded duration and durable idempotency; the long-lived lock
transaction must perform no business reads/writes or retain a business snapshot.
Configure its idle-in-transaction timeout above the maximum tick/check gap;
the existing migration-job 30 s timeout is not a worker setting.

Guarantee boundary: an advisory lock is not a fencing token for a transaction
on a different connection. Losing the lock during a business batch may overlap
with a replacement runner until cancellation/rollback completes. Capabilities
must use row claims/state predicates and idempotent effects; neither adapter
alone proves exactly-once business execution after a crash. Stronger fencing
would require an additional approved design. P7.2 must test this loss window,
not infer safety merely from a failed heartbeat. Likewise a try-lock proves
one simultaneous owner, not one historical winner for a wall-clock tick.

Verification: inspected the relay's existing transaction-lock implementation,
R2DBC security wrapper and pool cleanup; checked PostgreSQL try-lock/release
semantics. P7.1-P7.4/P7.15 must exercise both adapters against real PostgreSQL,
including cancellation during acquisition, backend loss and failed cleanup.

## P2.6 - Scheduling and Connection Bound

`SweepDefinition` is immutable `platform.api` metadata: canonical name,
owning module/feature, `SystemActor`, default/min/max interval, maximum batch
duration and maximum tick duration. An executable sweep bean in its owning
module's `infra` supplies deferred reactive work with batch boundaries and a
stop signal. Metadata and registry row must agree. No sweep body is implemented
here; the runner resides in `platform.infra.runtime`. Definitions and callbacks
never expose a raw connection, scheduler, arbitrary role name or lock key.

For each enabled definition, one runner-owned `Flux.interval` generates due
signals. Merge ordinary sweep signals and use one `concatMap` with prefetch 1
over the full acquire/work/async-release publisher. The outbox relay has its
own interval and its own single-permit `concatMap`. There are precisely two
lanes, never one lane per sweep. Thread count does not represent the permit;
subscription serialization covers the whole asynchronous tick lifetime.

Bound interval pressure explicitly. Before merging, each source has latest-only
backpressure handling; additionally keep at most one pending signal per sweep
in a bounded fair queue keyed by name. Repeated signals update/coalesce that
pending item without moving its queue position. Queue capacity equals the
number of registered sweeps, with fixed merge prefetch. Record coalescing;
never use an unbounded buffer. An overrun delays the next execution and does
not replay every missed interval. A fast source cannot displace another sweep
forever. Tests must count retained signals during a simulated 250 s overrun.

Acquire only after a lane permit is available. A loser releases its resources,
records contention and skips the signal. A failed tick records a sanitized
outcome and allows later ticks; invalid registry/configuration stops new ticks
and fails readiness. All subscriptions, intervals and stop signals belong to
the runner lifecycle. No nested `subscribe()` or detached batch can outlive its
tick; completion includes cleanup. Local durations use monotonic scheduler
time; domain eligibility uses persisted deadlines and the injected Clock.
Replica wall clocks are never used to grant ownership.

Healthy-session non-overlap follows from two independent invariants: `concatMap`
has at most one subscribed tick per lane per replica, and PostgreSQL grants
one exclusive owner for a stored key across replicas while the lock remains
held. P2.5 states the lock-loss boundary. The relay has a separate permit because
ARC-EXAM-013 allows roughly 250 s for a close burst's auto-submit drain; that
must not block a 200 ms relay tick. Ordinary sweeps intentionally serialize;
staleness metrics expose their queue delay.

TASK-PLAT6-DEFECT-003 budget: physically partition the existing worker maximum
as grading 4 + background business 4 + dedicated lock connections 2 = 10.
Do not retain the current ten-connection worker pool and add two. The two
lock leases consume two of the approved six background connections, leaving
four for business work. The long-running sweep and relay may each hold one
lock and use a business connection without borrowing a grading connection.
No third lock pool or permit is permitted. P3.5/P8.5 must verify the actual
configured sum and pool qualifiers, not just this arithmetic.

Acceptance clarification carried to P7.1/P7.23: repeated synchronized contenders
can prove one concurrent winner; staggered contenders can acquire sequentially
after release within the same nominal interval. The nine-column registry has
no durable per-tick claim, and adding one is outside the signed P0.6 scope.
Therefore strict once-per-logical-tick execution across delayed contenders or
crashes remains an unresolved requirement interpretation for the Architecture
Owner. Do not label a simultaneous-contention test as that stronger proof.
The singleton mechanism above satisfies the explicit P2.6 non-overlap design;
feature acceptance must resolve the stronger wording before claiming its DoD.

Verification: traced slow-source, fast-source, contention, failure and shutdown
paths; recomputed 4 + 4 + 2 = 10 and checked P0.6's two lanes. P7.5/P7.6/P7.22
must verify bounded backlog, relay independence and observed connection use.

## P2.7 - Ordered Shutdown

Adopt ARC-OPS-007 (also indexed as ARC-REL-008), carrying TASK-PLAT6-DEFECT-008.
Platform Ops' preStop contract first creates a pod-local drain marker, consumed
by readiness, then waits 30 s before SIGTERM. Marker I/O is off the event loop;
no new public drain endpoint is required. Readiness becomes refusing-traffic;
already admitted requests continue. SIGTERM also forces this state when no
preStop ran. API admission rejects new work while draining, including persistent
connections. The migration process keeps its separate lifecycle.

Let `G = WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE`, verified from
the pinned Boot 4.1.1 source as `Integer.MAX_VALUE - 1024`. Stop phases run in
descending order. Use Boot's constant, not an independently copied integer.

| Phase | Action | Proposed maximum |
| --- | --- | --- |
| `G + 1` | Stop sweep interval sources; discard pending signals; finish only the in-flight batch or roll it back; start no further batch | 5 s |
| `G` | Boot WebFlux graceful shutdown: stop accepting and finish admitted requests | 5 s |
| `G - 1` | Stop consumer intake/prefetch; ack only committed in-flight work; leave uncommitted deliveries recoverable | 5 s |
| `G - 2` | Release remaining sweep handles, roll back lock-only transactions, close the dedicated lock pool | 5 s |

Set `spring.lifecycle.timeout-per-shutdown-phase=5s` in the future runtime
configuration. Set listener-container phases explicitly; their defaults must
not stop broker resources ahead of the sweep/WebFlux phases. Keep connection
factories, Reactor resources and telemetry alive until their dependent work
and lock cleanup finish. Inventory other lifecycle phases, bean dependencies,
both web contexts and destroy callbacks: dependencies can override phase order.
The [Spring lifecycle contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html)
and [Boot graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)
make a single per-phase timeout insufficient as a total shutdown bound.

Budget: 60 s grace - 30 s preStop = 30 s after SIGTERM. Four potentially
waiting phases x 5 s = 20 s; allocate at most 5 s total to all remaining server
stop, resource-disposal, telemetry-flush and destroy work, leaving 5 s margin.
Thus 30 + 20 + 5 + 5 = 60 s. The 5 s remainder is a total, not an allowance
per extra phase. P4.14-P4.17 must configure underlying client shutdown deadlines
and P7.16 measure both parent and management contexts against a shared monotonic
deadline. Boot starting/stopping web-server phase `G - 1024` is included in
that remainder. New waiting phases must re-derive this budget; fail startup
if the declared bound is exceeded. A watchdog handles a stuck participant as
failed drain; Kubernetes' forced termination is a last resort, not success.
Kubernetes counts preStop within the [pod termination grace period](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination-flow).

Propose at most 4 s for a business batch, allowing 1 s for cancellation/rollback
within the sweep phase. Do not wait for the whole 250 s auto-submit drain.
The stop callback fires after the body reaches its transaction boundary;
runner resource cleanup may remain pending behind a shutdown release barrier.
The final lock phase opens that barrier and awaits `usingWhen` cleanup before
pool disposal. Normal tick completion releases immediately; only clean shutdown
defers release after the last body. A failed connection is invalidated promptly
even while draining. Never release a known-live lock while its body can still
start another batch. All callbacks are invoked exactly once.

Verification: inspected Boot 4.1.1 web lifecycle source and existing telemetry
cleanup; checked ordering and summed every budget category. These timing values
are design proposals requiring measured P7.14-P7.16 evidence, not proof that
the current application meets the grace period.

## P2.8 - Probe Surface

Resolve TASK-PLAT6-DEFECT-004 with a separate management listener on every role.
Propose management port 9091 and business port 8080; external configuration
must keep them distinct. Only the business port is in ingress Service targets.
Network policy permits management access from the cluster probe/operations
plane only. Do not route management through the public ingress or allow
API/worker pod identities to access pindist. Bind addresses must permit the
chosen kubelet probe mechanism; loopback-only binding is not assumed.

| Probe | Required inputs | Response and dependency policy |
| --- | --- | --- |
| `GET /health/live` | Process responsiveness and event-loop progress | Status only; no PostgreSQL, broker, Redis, IdP or collector round trip |
| `GET /health/ready` | PostgreSQL reachable, migration compatibility verified, role-required secrets loaded, all configuration/role validators passed, not draining | 200 UP or 503 DOWN; bounded checks; fail closed on unknown required state |
| `GET /health/startup` | Initial secret fetch, config/manifest validation, required local cache warm complete | Status only; readiness remains false until startup completes |

Use named health groups with explicit members, not the aggregate health tree.
Hide details/components for unauthenticated probes; do not expose environment,
version, secret names, schema names, diagnostic exception messages or discovery
links. Unknown management paths return 404. Worker has no business handlers;
pindist has only the reserved route when shipped, with probes on management.
Existing OTLP push export continues independently; installing a Prometheus
registry does not itself authorize a public scrape endpoint.

Read-only migration checks use the release compatibility manifest and expected
per-module migration state from FEAT-PLAT-005, not simply the latest numeric
version. They must allow the documented N-1 code/newer expanded schema rollback.
Do not run Flyway, load migrator credentials or add a health-only connection
pool. Reuse bounded workload access with narrow probe permissions; any missing
history-read privilege is resolved explicitly in the grant matrix by its owner.
Probe SQL contains no business query and a timeout makes readiness false.

Only mandatory role secrets are checked. API never tests possession of the
retrieval KEK; pindist uses KMS references/identity, never retrieves key material.
Redis and telemetry outages remain non-critical; local cache warm must have a
bounded fallback when its contents are reconstructible. Broker/notification
health is observed separately and must not remove all API replicas from service.
The future business exam-readiness gate remains FEAT-OPS-001's operator surface.

Verification: compared with architecture sections 17.4/20.1 and signed P0.4;
checked current Flyway-disabled application and observability health component.
P4.13/P6.6/P6.7/P7.13 must test actual listeners, status-only bodies, dependency
failures and ingress isolation; topology isolation is not proven by this design.

## P2.9 - In-Process Bulkheads

ARC-REL-003 requires separate bounded-elastic schedulers for exam work and
administrative/report work. Create named, lifecycle-owned schedulers in
`platform.infra.runtime`, injected into web/infrastructure boundaries. Never
make domain classes select Reactor schedulers. Keep WebFlux and R2DBC reactive;
only unavoidable blocking SDK/file work is wrapped in a deferred publisher
and offloaded. Scheduler selection does not authorize blocking database access.
Avoid the shared global bounded-elastic scheduler for these workloads.

Propose an initial exam scheduler cap of 8 threads and admin/report cap of 2,
each with finite queued-task limits (32 and 16 per backing thread respectively)
and a 60 s idle TTL. These are tunable recommendations, not architecture numbers
or reserved CPU guarantees. Account for Reactor's per-thread queue limit when
deriving the aggregate memory bound. Admission controls in P2.10 cap in-flight
requests before scheduling, so scheduler queues cannot become the admission
queue. Exhaustion is a bounded, sanitized overload response, not an unbounded
retry. Domain CPU-heavy grading uses its separately bounded worker execution
lane; exam/admin schedulers do not consume its four permits.

Use the existing `examPathConnectionFactory` qualifier for the API's reserved
8-connection pool and the 6-connection API general pool for admin/reports.
Do not create a replacement pool, combine the pools or fall back to general
connections when exam capacity is exhausted. Preserve the existing tracing and
security decorators. Retain Reactor Context (actor, tenant, correlation and
trace) across scheduler boundaries; no tenant state is kept in an ordinary
ThreadLocal. P3.5's worker partition and pindist's separate 5-connection pool
remain independent of these API bulkheads.

The complete six-control set is: bounded heap/restart-on-threshold; route
concurrency limits; per-route-class statement timeouts; reserved exam pool;
separate schedulers; and the canary observation window. The feature card's risk
row omits the last two. Architecture section 17.6 prescribes a ten-minute
observation of 5% canary traffic against exam-path p95, 5xx, feedback SLO burn
and error-log rate, with rollback on failure; FEAT-OPS-007 owns deployment.
Runtime exposes limits and telemetry but does not author its Kubernetes rollout.

Section 7.4/ARC-EXAM-013 also requires safely interruptible sweeps: bounded
transactions, durable selection predicates, restartable progress and no
in-memory-only checkpoint. A sweep never runs a whole long drain as one
uncancellable task. P7.14/P7.16 and capability tests prove recovery after
interruption; no scheduler setting alone supplies it.

Verification: compared all six controls with architecture sections 7.4, 14.4,
15.2 and 17.6; inspected existing pool configuration and tracing wrappers.
P4.18 and later saturation tests must show admin exhaustion leaves exam
admission, connections and scheduler capacity available.

## P2.10 - Admission and Statement Timeouts

PLAN-RECOMMENDATION: the baseline names the mechanisms but quantifies neither
route concurrency nor statement timeouts. Propose these initial per-replica
values for capacity testing; Architecture/Platform Ops and FEAT-OPS-005 must
ratify production tuning. Enforcement and invalid-value rejection remain
blocking requirements whatever values are eventually ratified.

| Route class | Per-route in-flight cap | Aggregate class cap | Connection acquisition | Per-statement timeout | Whole request deadline |
| --- | --- | --- | --- | --- | --- |
| Exam path | 32 | 64 | 100 ms | 500 ms | 2 s |
| Administrative | 8 | 12 | 250 ms | 1 s | 3 s |
| Report | 2 | 2 | 250 ms | 2 s | 4 s |

Administrative and report requests additionally share a combined cap of 12;
report work consumes the general API pool only. Caps are admission limits,
not new connection reservations. Pindist's reserved read is administrative
with a tighter aggregate cap of 5 on its own pool. Worker batch timeouts are
separate from HTTP classes and must meet P2.7. These deadlines are protective
ceilings, not claims that exam p95 meets its much tighter performance target.

Classify every registered route by stable method/template/route ID in a
committed infrastructure policy table. Missing or conflicting classification
fails boot. Do not infer a class from a request header or a raw path prefix;
known exam routes must not accidentally fall into an administrative default.
Telemetry's existing `EXAM_ENTRY`, `GRADING` and `STANDARD` categories remain
independent: exam entry maps to exam admission, while other exam routes need
explicit route entries even if their telemetry category is STANDARD. No kernel
enum change or use of telemetry metadata as authorization policy is needed.

Acquire per-route and class permits atomically/non-blockingly after route
matching but before invoking the body. On overload, release any partial
acquisition and return sanitized RFC 9457 503 with bounded `Retry-After` and
correlation ID using the existing error mapper. This is capacity exhaustion;
per-principal rate limits remain separate. No waiting queue or retry loop is
introduced. Release permits exactly once on success, error, timeout or
cancellation, including a client disconnect and a handler throwing before
returning a publisher. Streaming holds the permit until stream termination.

Carry the validated policy in Reactor Context to the existing secured R2DBC
adapter. After BEGIN and the mandatory R9 role/scope statements, install the
transaction-local statement timeout using a bound `set_config` value and
preserve the existing controlled search path. Never set a session timeout
that leaks to the next borrower, or let a caller supply raw SQL/setting names.
All request transactions receive the class policy; standalone business SQL
outside a secured transaction remains forbidden. A reactive timeout must
cancel the underlying query and await rollback/reset, not merely abandon the
HTTP subscriber while PostgreSQL continues working. The whole-request deadline
also bounds a sequence of individually short statements; query-count budgets
from FEAT-OBS-001 continue to apply independently.

Verification: checked ordering against `SecurityContextInitializer` and current
telemetry classes; all proposed request deadlines fit the 5 s WebFlux drain.
P4.19/P4.20 tests must cover route/class contention, pool exhaustion, timeout
rollback, context preservation and cancellation permit leaks on real PostgreSQL.

## P2.11 - Heap Bound and Restart Threshold

Under the same PLAN-RECOMMENDATION, architecture ARC-RISK-006/section 7.4
requires bounded heap and restart-on-threshold but supplies no heap percentage,
occupancy threshold or duration. Proposed starting budgets use the section
17.1 container limits, not resource requests:

| Workload | Memory request / limit | Proposed maximum heap | Proposed direct-buffer cap | Remaining limit for other native memory and margin |
| --- | --- | --- | --- | --- |
| API | 3 Gi / 4 Gi | 2048 Mi | 256 Mi | 1792 Mi |
| Worker | 2 Gi / 3 Gi | 1536 Mi | 256 Mi | 1280 Mi |
| Pindist | 1 Gi / 1 Gi | 512 Mi | 128 Mi | 384 Mi |

Set an explicit per-role `-Xmx` and `-XX:MaxDirectMemorySize`; do not combine
contradictory heap percentage overrides. Threads, stacks, metaspace, code cache,
Netty/native allocations and the operating system consume the remainder; it
is not extra heap. Boot validates declared heap/direct caps against the actual
container limit. Platform Ops supplies external JVM/deployment values in P3.6;
staging load tests must measure total resident memory, especially pindist's
smaller allowance. The Java 21 [launcher specification](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)
defines the JVM sizing options; these proposed values are project decisions.

Propose a restart trigger at heap used >= 85% of maximum for 60 continuous
seconds, sampled once per second using a monotonic duration. Any sample below
threshold resets the window. A companion process/container-memory trigger at
>= 90% of the limit for 10 s covers native pressure. The central runtime
monitor owns bounded sampling; use JVM/container metrics, never forced GC or
heap dumps to evaluate health. Missing container-limit input fails config
validation rather than treating the host's RAM as the container allowance.

Crossing a sustained threshold latches readiness down and a local restart
reason, stops new admission and marks liveness failed. Kubelet then initiates
the normal preStop/SIGTERM sequence from P2.7 and restarts the container.
Alert on repeated restarts; rollout policy and backoff must avoid fleet-wide
restart storms. Recovery of memory does not clear a latched restart. A JVM
out-of-memory fail-fast option is a last-resort crash path, not evidence of
graceful drain. Automatic heap dumps are disabled by default because they may
contain secrets and candidate data; operator diagnostics need separate controls.

Verification: arithmetic checked against the three architecture memory limits;
heap is 50% of each limit and the remainder is positive after direct buffers.
P4.21 must test threshold persistence/reset/latching with controlled time;
P7.16 and capacity testing must measure restart/drain behavior and native headroom.
No threshold is presented as ratified or enabled by this documentation.

## P2.12 - Pindist Route Allowlist

Adopt exactly `GET /api/v1/exam-sessions/{id}/pin-distribution` from architecture
sections 10.2 and 17.4. Section 8.2's unversioned spelling is tracked as
TASK-PLAT6-OBS-002; do not serve both. The sole business route belongs to
FEAT-EXAM-003, and its retrieval-key grant remains FEAT-EXAM-002's A4 evidence.
The Phase 0 artifact may expose zero business routes while that feature is not
shipped, as the signed DoR permits. A delivered FEAT-EXAM-003 requires exactly
one matching route; zero, duplicates or extra routes fail boot.

After route registration and before readiness or listener activation, enumerate
all HandlerMappings in the business context: annotated handlers, functional
routers (including nested predicates), resource handlers and framework-added
routes. Compare actual method/template tuples and owning user classes with
the golden manifest and `PolicyProtectedRoute` descriptors. An opaque custom
RouterFunction whose predicate cannot be enumerated must fail validation;
metadata alone is not evidence of what it serves. Wildcards, extra methods,
unbounded method predicates, aliases, nested prefixes, static resources and
unexpected error/documentation/Actuator mappings are not permitted.

Management is a distinct context and listener: permit only the three P2.8
probe mappings there. A management mapping registered on the business listener
is an error, even if its path is allowed on management. No parent-context
endpoint inheritance may expose an API endpoint through management. Snapshot
validation must inspect both contexts and the effective listener addresses.

A narrowly scoped early pindist path/method guard returns 404 for everything
outside the reserved template, before authentication can turn an unknown path
into 401/403. Use Spring's parsed PathPattern and canonical request-path rules;
reject ambiguous encoded separators, duplicate slashes and unexpected suffixes.
Do not implement a loose `startsWith` test or double-decode paths. Reject HEAD
and OPTIONS unless a later explicitly approved contract adds them; do not let
automatic HEAD/CORS handling widen the GET-only contract. The allowed route
continues through OAuth2 authentication, workforce policy, tenant checks and
audit. No global security disable or permit-all business chain is introduced.

Role resolution consumes only boot environment, never header/query/cookie/body
data. Test forged role/profile inputs and requests to probes, arbitrary paths,
the unversioned alias and non-GET methods on the business port. NetworkPolicy
and ingress isolation tests remain required even if application 404s pass.

Verification: compared the exact versioned template with sections 10.2/17.4,
P0.4 and the new manifest; inspected the existing functional route descriptor
and visitor interfaces. P4.11/P6.5/P7.13 own the executable assertion and tests.

## P2.13 - Fail-Closed Startup Validation

This is the runtime limb of architecture section 17.5/ARC-OPS-004. Carry
TASK-PLAT6-DEFECT-007: ARC-VERIFY-018 is already attributed to persistence in
plan section 14.4; this design does not mint a replacement identifier or claim
the other features' validators. All failures abort startup with a bounded
error code and safe field name, never a credential, SQL driver message or
silent clamp. There is no degraded mode or bypass flag for these invariants.

| Stage | Check | Failure behavior / follow-on test |
| --- | --- | --- |
| Environment prepared | Exactly one explicit active role after profile expansion; it agrees with workload DB login and observability role; migrate bootstrap is separate | Abort before role beans/listeners; P7.11/P7.12 |
| Candidate metadata | P2.1 declaration coverage/closed values on all candidate entry points, including profile-excluded definitions | Abort before instantiation; negative rule fixtures |
| Configuration bound | Required external settings present; pool sums, scheduler limits, adapter/pooler compatibility, heap and all time bounds valid | Abort, no fallback; P7.9/P7.22 |
| Worker registry load | Registered sweep names unique; every bean has a row; every enabled row has a bean; names/modules/features/actors/defaults agree | Abort; disabled future rows may lack a bean; P7.7 |
| Registry identity | All stored keys and names unique across enabled and disabled rows; no runtime derivation or override | Abort; P7.8 |
| Bean/route registration complete | Exact golden entry-point match, prohibited categories absent, P2.12 allowlist and isolated management routes | Abort before admitting traffic; P7.10/P7.13 |
| Lifecycle ready | All required validations and startup checks passed before sweeps/consumers or accepting traffic | Activate only through one startup gate |

Worker alone reads the registry using its granted SELECT; API and pindist
must not acquire new registry permissions to run a worker-only validator.
They instead require zero registered sweep beans. Every registered but
disabled worker bean still needs a valid row. Enabled business rows require
their owning feature's delivered status and security mapping. A missing or
unreadable registry is a worker boot failure, not an empty schedule.

Proposed outer interval bounds are 100 ms through 24 h inclusive, finite
integral milliseconds with checked duration conversion. Each definition
further narrows the range. Until owners approve alternatives, relay uses
exactly 200 ms, auto-submit exactly 10000 ms, and stale reclaim exactly
30000 ms. Other seed rows stay disabled until their owners supply bounds and
eligibility deadlines; the 60000 ms placeholder is not permission to enable.
Reject unknown override names, nonpositive/out-of-range values and a default
outside a declared range. Configuration changes use the same validator as boot.

Proposed tick cap: positive and at most 300 s, with a positive batch bound
at most 4 s and no greater than the tick cap. A tick may exceed its interval;
that is a coalescing case, not a configuration error. Lock checkout, query and
cleanup limits must be positive and fit their phase. Pooler/server timeout
configuration must allow the lock-only transaction's declared tick/check gaps.
Do not silently increase a database timeout at each tick.

Validate `preStop=30s`, grace `60s`, per-phase timeout `5s`, batch `<=4s`, and
the sum of every declared waiting phase plus teardown against P2.7. A future
change may use different reviewed values only when the full inequality still
holds; simply checking `timeout < 30s` is insufficient. Unknown lifecycle
participants need a declared bound and ordering assessment. Validate numeric
admission/scheduler/heap settings from P2.9-P2.11 with positive finite values.

Use early environment/bean-definition validation for role safety and a
pre-lifecycle registration check for complete bean/route sets. Sweep runners
and broker listeners must not auto-start before that gate; ordinary
ApplicationRunner validation is too late to be the sole safety barrier.
P4.8/P4.10 must test the gate with a forbidden bean whose constructor or listener
would perform an external action and verify it never activates.

Verification: cross-checked every task-listed validator, disabled-row behavior,
role grants and shutdown inequality against P2.1-P2.12 and the existing external
configuration. All proposed bounds have explicit failure behavior and tests.

## P2.14 - Sweep Security Context

Consume existing `ActorContext`, `SystemActor`, `CorrelationId` and the kernel
`Clock`; add no parallel actor model. The runner creates a platform-system
actor from the validated definition, generates a fresh correlation ID per tick,
and propagates it through Reactor Context. It never accepts an actor/role from
a request or a database string converted by unchecked `valueOf`. Map registry
values through the closed vocabulary and compare them with code declarations.

| Sweep | Required actor | Business module role | Platform operation |
| --- | --- | --- | --- |
| Auto-submit | `AUTO_SUBMIT_SWEEPER` | `app_delivery` | Proposed `AUTO_SUBMIT_SWEEP` |
| Stale-claim reclaim | `GRADING_WORKER` | `app_grading` | Proposed `STALE_CLAIM_RECLAIM` |
| PIN-material destruction | `RETENTION_ENGINE` | `app_examaccess` | Existing `RETENTION_SWEEP` |
| Retention | `RETENTION_ENGINE` | Each owning module's narrow role | Existing `RETENTION_SWEEP` |
| IdP reconciliation | `IDP_RECONCILER` | `app_iam` | Existing `RECONCILIATION` |
| Outbox relay | `OUTBOX_RELAY` | `app_outbox_relay` | Existing `OUTBOX_RELAY` |

The two proposed operations extend the existing `shared.api.PlatformOperation`
enumeration during P4.7 with its owner-reviewed actor predicates; no
`shared.kernel` type or free-form marker is introduced. They are necessary
because the current enum permits neither AUTO_SUBMIT_SWEEPER nor GRADING_WORKER
for a platform sweep. Do not impersonate RETENTION_ENGINE to fit an existing
predicate. Before a capability is enabled, its closed name/actor/role/operation
mapping and any required platform RLS policy must be implemented and tested.

Every transaction begins through the existing `SecurityContextInitializer`:
BEGIN, then `SET LOCAL ROLE <enumerated role>`, then
`SET LOCAL app.platform_scope = '<enumerated operation>'`; the controlled search
path and timeout follow, then the first query. `current_setting('app.platform_scope',
false)` must succeed and equal the declared marker. Missing context is an
error, never an RLS bypass. No later role switch, arbitrary role interpolation,
`BYPASSRLS`, fake tenant or disabled forced RLS is allowed. Application scopes
must expose narrow declaration/transaction ports through `platform.api` so
capability code never imports `platform.infra.persistence`.

The two dedicated lock connections need the same R9 initialization for their
lock SQL. Registry SELECT is granted to the login `app_worker`, not its
business member roles. Therefore use a narrow registry/coordination adapter
which installs `SET LOCAL ROLE app_worker` and a proposed closed
`SWEEP_REGISTRY_READ` operation before registry/lock-management SQL. Its
statement surface is registry SELECT and the adapter's lock functions only;
it cannot be a general business transaction port. A boot registry read has no
business ActorContext; make that a dedicated internal read transaction entry
point, not a generic actor-validation escape. During ticks the coordination
operation accepts only the validated definition's enumerated system actor.
P4.7 must extend the existing initializer's closed policy explicitly for this
login-role case; the current `AssumableDatabaseRole` lacks it. Do not grant
registry reads to every module role or bypass the decorator to make it work.
Session-lock acquisition/check/release use short initialized transactions on
the retained connection; transaction-lock mode retains its initialized
coordination transaction. No business SQL runs on that connection.

Platform scope does not automatically authorize tenant-table access. For a
tenant mutation, establish a separate bounded transaction with that tenant's
`ActorContext.system`, module role and `app.tenant_id`, using tenant-parameterized
queries and existing RLS. Never mix tenant and platform context in one
transaction. Where a reviewed platform policy selects work across tenants,
the capability owns that policy and the narrow selection query; the runtime
runner does not acquire union privileges over all modules.

R4: the sweep is an infrastructure entry point that invokes a bounded
application operation, not a handler calling another handler. Each business
batch has one transaction; no ambient outer business transaction wraps multiple
handlers/batches. The lock-only transaction is an independent coordination
resource. R8: each tenant mutation emits the audit event in that same business
transaction with the system actor and affected tenant; outbox events share its
commit. Fail audit append -> rollback the mutation. Tick logs/metrics cannot
substitute. Cross-module propagation follows R2/R7, not direct schema writes.

Verification: inspected `PlatformOperation`, `SystemActor`,
`DatabaseSecurityScopes`, `AssumableDatabaseRole` and initializer statement
ordering. Identified the missing closed operations/login-role coordination
path rather than claiming current support. P7.20/P7.21 must record first SQL,
actor/tenant attribution, missing-context failure and reset after cancellation.

## P2.15 - Two Blocking Conformance Rules

Implement in the existing `conformanceTest` source set and CI stage 4 BLOCK,
using the current ArchUnit/class-file and fixture patterns. These supplement
R1-R10, never replace R6, R9 or R10. The suite must prove each rule rejects its
negative fixtures and also run it against production classes. No new globally
shared service, generic repository or domain-framework dependency is introduced.

### Role Declaration Rule

Classify every production slice `Endpoint`, actual WebFlux endpoint/router,
broker listener/consumer container and registered sweep definition/factory.
Use assignability, listener annotations and bean-factory return metadata as
well as naming; renaming an Endpoint must not evade the check. Count exactly
one direct P2.1 declaration at its registration site. Inspect all candidates,
not just the selected profile's instantiated beans. A factory declaration
cannot conflict with the produced type or be inherited from a configuration.
Each role value must be a unique literal from RuntimeRole; allowed categories
must match all its declared roles. Infrastructure health/telemetry monitors
are explicitly classified support, not silently treated as business sweeps.

| Negative fixture | Required violation |
| --- | --- |
| Endpoint with no annotation or only bare `@Profile` | Missing role declaration |
| Unannotated `@Bean` consumer or renamed listener | Missing declaration at actual registration site |
| Both factory and produced type declare a role; duplicate meta-declarations | Multiple/ambiguous declarations |
| Empty array, duplicate role, typo, placeholder, negation or profile expression | Value outside closed role vocabulary |
| Consumer/sweep declares API or pindist; ordinary route declares worker/pindist | Forbidden role/category combination |
| Domain class carries RoleScoped or a derived role stereotype | R6/role boundary violation even if a direct framework-import rule misses the transitive dependency |
| Opaque route, raw listener container or excluded-profile bean omitted from inventory | Unclassified entry point / golden inventory mismatch |

Positive fixtures cover each role, a genuinely shared support adapter, a valid
multi-role declaration and a uniquely annotated factory. Errors report class
or method identity and bounded rule codes. Production contains no broad
annotation-based suppression switch. Boot manifest checks supplement static
coverage for programmatic registration and framework-generated routes.

### Central Scheduling and Lock Rule

Only named runner source factories in `platform.infra.runtime` may create
periodic Reactor sources. Keep the exact approved class/method list in the
rule; permitting every class in `platform.infra` is too broad. The central
source factory owns sweep/relay intervals and the operational health/heap
sampling sources, which are distinct from role-restricted business sweeps.
Only `SessionSweepLock` and `TransactionSweepLock` may execute advisory lock
acquisition/release/ownership SQL through the secured coordination path.
Other modules use `platform.api.SweepLock`, never the concrete adapters.

Detect all `Flux.interval` overloads, periodic `Scheduler`/executor scheduling,
`@Scheduled`, Timer and self-rescheduling/repeat-delay equivalents used to
create business tick loops. For SQL use the existing parser/scanner approach
over literals, constants and SQL resources, including schema-qualified lock
functions and dynamic construction that cannot be statically resolved. Cover
try, blocking, session, transaction and shared advisory-lock variants, and
unlock/unlock-all. Uninspectable dynamic lock SQL is rejected, not ignored.
Finite Reactor delays/backoff for request retry are not automatically sweeps;
the rule must distinguish them and have a positive fixture for that case.

| Negative fixture | Required violation |
| --- | --- |
| Capability infra calls Flux.interval or periodic executor | Tick source outside the central runner |
| Annotated timer or recursive delayed sweep in a slice/helper | Alternate scheduler bypass |
| Unapproved helper under platform.infra creates an interval | Package membership alone is insufficient |
| Module or old RelaySingleton executes pg_try_advisory_xact_lock | Second advisory-lock mechanism |
| Blocking/shared lock call, schema-qualified SQL resource or concatenated function name | Lock operation outside the adapters |
| Capability imports a concrete lock adapter | R2 plus central lock boundary violation |

Positive fixtures cover both approved adapters, runner sources, a capability
declaring its work via the API and a finite non-scheduler retry. Negative test
fixtures are loaded explicitly and never packaged in the production artifact.
Third-party bytecode is not scanned as application code, but application
factories that configure third-party listeners/schedulers are in scope.

Migration of the current relay is required before the new lock rule is enabled:
P4.25/P7.19 must land the adapter adoption and gate activation coherently. The
current relay direct SQL is a known future violation, not a permanent exclusion
and not a reason to leave the final CI gate red. Likewise the current reference
Endpoint needs its P4.1/P4.10 role annotation before the new rule goes blocking.
New cleanup/coordination code must continue to satisfy the existing R9 rule,
which only permits transaction opens inside SecurityContextInitializer (and
its tracing wrapper); adding lock adapters to a blanket exemption is forbidden.

Verification: inspected existing R2/R6/R9/R10 rules and negative-fixture style;
enumerated current advisory SQL and role-profile declarations. Each new rule
has distinct positive/negative coverage and a production-scan obligation.
P4.24/P4.25/P8.1 implement these specifications; no new executable rule is
claimed to exist in this design-only range.

## Execution Summary

Executed range: `P2.1-P2.15`, strictly in order. Each completion marker was
saved immediately after its design and acceptance review. All 15 design tasks
are complete; none failed and none remain in the requested range. Runtime
implementation, migrations, deployment and new conformance tests remain in
their original later phases.

| Completed tasks | Deliverables |
| --- | --- |
| P2.1-P2.2 | Role API/declaration design, golden JSON format, generation/comparison procedure, three role manifests |
| P2.3-P2.6 | Registry schema, reproducible key vectors, reactive lock contracts, two adapters, bounded scheduling and connection arithmetic |
| P2.7-P2.9 | Ordered shutdown and total budget, isolated probes, in-process bulkheads |
| P2.10-P2.13 | Proposed admission/time/heap limits, exact reserved-route check, fail-closed startup validation |
| P2.14-P2.15 | Security-context integration design and two conformance specifications with negative fixtures |

Files persisted:

- `docs/evidence/FEAT-PLAT-006/phase-2-architecture-design.md` (this record).
- `contracts/runtime-roles/api.json`.
- `contracts/runtime-roles/worker.json`.
- `contracts/runtime-roles/pindist.json`.
- `tasks/foundation/runtime/tasks.md` (only Phase 2 markers and evidence link).

Verification completed on 2026-10-11:

```text
./ci/verify-runtime-role-dor
./ci/verify-runtime-registry-budget-approval
./gradlew compileJava compileTestJava --console=plain
./gradlew :test \
  --tests 'org.meldtech.platform.platform.infra.persistence.WorkloadConnectionPoolConfigurationTest' \
  conformanceTest \
  --tests 'org.meldtech.platform.conformance.R2*Tests' \
  --tests 'org.meldtech.platform.conformance.R6*Tests' \
  --tests 'org.meldtech.platform.conformance.R9*Tests' \
  --tests 'org.meldtech.platform.conformance.R10*Tests' --console=plain
git diff --check
```

Both signed-record verifiers and the compile check passed. Eighteen tests
passed in five suites (pool configuration 3; R2 3; R6 8; R9 2; R10 2), with
zero failures/errors/skips. These validate existing prerequisites, not the
future runtime design. Additional deterministic checks parsed the three JSON
manifests with duplicate-key rejection, matched their role/workload identities
to P0.4, checked concrete endpoint paths, all 15 ordered evidence headings and
task markers, Markdown fences, lock-key uniqueness, and heap/shutdown arithmetic.
No Docker integration suite, full build, staging isolation test or runtime
timing drill was run for this documentation/contract-design change.

Risks and implementation prerequisites:

- Resolve strict once-per-logical-tick wording with the Architecture Owner
  before claiming P7.1/P7.23 or feature DoD. Stored advisory keys supply mutual
  exclusion, not durable tick deduplication or cross-connection fencing. Any
  additional registry columns/mechanism require the existing P0.6 change policy.
- Capability owners must confirm unknown cadences, retention coordination
  ownership and required platform policies before enabling their rows. Provisional
  intervals never authorize scheduling.
- Production admission, scheduler, heap and timeout proposals require owner
  review and load/drain evidence. The 60 s shutdown bound includes both web
  contexts and every dependency cleanup callback; no measured guarantee exists yet.
- P4.7 must supply the missing closed platform operations and the secured
  registry/login-role coordination path. P7.19 must retire relay-local lock SQL
  before the central-lock conformance gate becomes blocking.
- Real workload identities, network denial, the retrieval-key grant/A4,
  ARC-VERIFY-007's mid-exam drill and the A8 capacity proof retain their existing
  owners and gates. Neither these manifests nor passing baseline tests discharge them.

Recommended next chunk: `P3.1-P3.10` (data and infrastructure), after checking
its task-specific prerequisites and the design qualifications above. Start with
the signed P0.6-approved additive registry and keep future capability rows
disabled. The pre-existing untracked Platform Ops provisioning signature was
not modified; no task outside P2.1-P2.15 was executed.
