# Runtime Connection Pools

The worker's approved maximum is ten physical connections per replica:

| Qualifier | Pool name | Maximum | Purpose |
| --- | --- | --- | --- |
| `workerConnectionFactory` | `worker` | 4 | Background business transactions |
| `gradingConnectionFactory` | `grading` | 4 | Reserved grading transactions |
| `sweepLockConnectionFactory` | `sweep-lock` | 2 | One serialized sweep lane and one independent outbox relay lane |

All three use the externally supplied `CBT_DATABASE_URL` and
`cbt.database.roles.app-worker.password` from the database secret tree. They
log in as `app_worker`; credentials are not embedded. Worker startup rejects
allocation changes, a different login role, or nested pooled URLs. Pool
creation retains the existing database security-context and telemetry wrappers.
The pool qualifiers identify distinct physical pools, with no fallback to the
grading reserve. They initialize with zero connections.
The background factory remains the primary worker factory so Boot's default
`DatabaseClient` uses it. Grading and lock consumers must request their explicit
qualifiers; adding the reserves does not remove the existing default client.

This allocation replaces the former ten-connection worker business pool.
API remains six general plus eight reserved exam-path connections; PIN
distribution remains five. At the worker HPA ceiling, the lock pool accounts
for 24 x 2 = 48 of the existing 24 x 10 = 240 worker connections.

P8.5 envelope registration: `worker = grading(4) + background(4) + locks(2)`.
The later P8.5 task must include these pools when reporting `allocated`,
`allocated_with_headroom`, `committed` and `headroom_remaining`; this document
registers the inputs and does not claim the release-stage envelope check.

The runner and ordered shutdown remain Phase 4 work. A lock lease must be released or invalidated before its
connection returns to the pool. The existing context reset is not an advisory
lock release mechanism. Real-PostgreSQL allocation coverage is provided by
`WorkerPoolIsolationIntegrationTest`; context reset and authorization remain
covered by the persistence security suites.

## Lock Adapter Selection

`cbt.runtime.sweep-lock.mode` selects `session` (default) or `transaction` on
the worker. Supply these optional properties through external application
configuration or command-line arguments.
Only one `SweepLock` bean is registered. API and pindist register neither.

Set `cbt.runtime.sweep-lock.pooling-mode=transaction` when the database URL points to PgBouncer
transaction pooling; it defaults to `direct`. Startup rejects unknown values
and rejects session locks with transaction pooling. The
[T-15 adoption guide](../runtime-pgbouncer-seam.md) records the required capacity,
context-isolation and live-pooler evidence before switching.

The transaction adapter holds one initialized lock-only transaction on the
dedicated pool for the lifetime of its handle. It rolls back on release and
never commits business data. Acquisition, ownership checks and each normal
cleanup operation have two-second timeouts. Uncertain acquisition or cleanup
discards the connection. Configure the database/pooler's idle transaction
timeout above the maximum tick/check gap; the migration job's timeout is not
a suitable worker default. Session and transaction modes share PostgreSQL's
bigint advisory-lock namespace and the persisted registry key.

The adapter supplies mutual exclusion while ownership is held, not a durable
once-per-tick claim or a fencing token for a separate business transaction.
The PgBouncer deployment itself is not covered by the direct PostgreSQL tests.
