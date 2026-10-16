# T-15: PgBouncer Transaction-Pooling Seam

FEAT-PLAT-006 P10.6 records a decision already implemented: the worker ships
both `SessionSweepLock` and `TransactionSweepLock` behind `platform.api.SweepLock`.
Transaction mode is an existing tested adapter, not a future redesign. The
default today is session locks against direct PostgreSQL. PgBouncer adoption
and deployed pooler evidence are not claimed by the direct-PostgreSQL tests.

| Setting | Current direct connection | Transaction-pooling deployment |
|---|---|---|
| `cbt.runtime.sweep-lock.mode` | `session` (default) | `transaction` |
| `cbt.runtime.sweep-lock.pooling-mode` | `direct` (default) | `transaction` |
| `CBT_DATABASE_URL` | Approved PostgreSQL endpoint | Approved PgBouncer endpoint, supplied externally |

Only the worker creates a lock adapter. `SweepLockConfiguration` rejects
unknown values and rejects session mode with declared transaction pooling.
This declaration does not discover a hidden pooler automatically: Platform Ops
must keep configuration consistent with the actual connection endpoint.

## What Changes

Session mode retains the dedicated connection and session advisory lock for the
tick. Transaction mode opens a lock-only transaction, obtains
`pg_try_advisory_xact_lock` using the same stored registry key, and keeps that
transaction open until the handle closes. It pins the backend for the tick;
ending the transaction rolls back and releases the lock. Business batches use
separate secured transactions and are never committed by the lock adapter.

Both modes use the same bigint advisory-lock namespace, skip on contention,
monitor ownership and clean up on success/error/cancellation. The transaction
adapter does not make a series of business batches one atomic transaction and
does not provide fencing or durable once-per-tick ownership. Do not change keys,
create a second relay mechanism or move lock ownership into individual batches.

## Prerequisites Before Adoption

1. Platform Ops and the persistence owner approve the T-15 capacity escalation,
   pooler endpoint, authentication, backend allocation and transaction-pooling
   configuration. Keep credentials in the existing secret configuration.
2. Retain the per-worker allocation: grading 4, background 4, lock pool 2, total
   10. Long-lived lock transactions still occupy up to two backends per replica;
   transaction pooling does not remove those from the stage-4a envelope. Re-run
   capacity validation against the deployed pooler/server limits, including HPA
   ceilings, migration connections and headroom.
3. Review database and pooler idle-transaction, query, connection and shutdown
   timeouts against the maximum tick and ownership-check gap. Do not copy the
   migration job's timeout into the worker. Adapter acquisition/check/cleanup
   operations have two-second command bounds; uncertain connections are discarded.
4. Verify R9 context installation/reset, permitted `SET LOCAL ROLE`, tenant and
   platform markers, and required driver/session behavior through the actual
   pooler. Passing direct PostgreSQL tests alone does not prove pooler behavior.
5. Run real pooler integration and staging drills for N-replica contention,
   backend loss, cancelled/failed acquisition, bounded batches, rollback,
   handle release and graceful shutdown. Verify exactly one active body while
   ownership is held, no leaked backend/transaction and recovery from durable state.
6. Re-run relay adoption, context-isolation and connection-budget suites; retain
   results and approve the deployment change before changing the endpoint.

Drain workers through the [shutdown contract](shutdown-contract.md), deploy the
endpoint and both settings together, and check locks, pool saturation, skipped
ticks and successful batches. To return to direct PostgreSQL, drain first and
restore the approved endpoint/configuration together. Never switch to session
locks while the endpoint still uses transaction pooling. No registry migration
or key rewrite is part of this switch.

Evidence: `SweepLockConfigurationTest`, `SessionSweepLockTest`,
`TransactionSweepLockTest`, `SessionSweepLockIntegrationTest` (both adapters)
and `SweepRunnerContentionIntegrationTest` (both adapters).
See [connection pools](runbooks/runtime-connection-pools.md) and
[relay adoption](runtime-relay-adoption.md).
