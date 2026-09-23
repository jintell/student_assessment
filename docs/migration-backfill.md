# Backfill Authoring Guide

Backfills are application data work, not schema migration work. They run as a
named operation in the `worker` profile through `ResumableBackfillHarness` and
never in the migration Job or a Flyway script.

## Define the Operation

Create a `BackfillDefinition` with a stable name, positive version, owning
module, immutable definition checksum, batch size, and rows-per-second limit.
Register `<name>:<version>` with `BackfillOperationRegistry` so the worker can
select it using `cbt.backfill.operation`.

Changing query semantics, row transformation, key ordering, or eligibility is
a new definition version and checksum. The harness refuses to continue a
checkpoint whose checksum differs from the current definition.

## Batch by a Stable Key Range

At the start of a run, persist an inclusive upper bound and an exclusive
cursor. Each batch selects at most `batchSize` eligible rows satisfying:

```text
cursor < primary_key <= upper_bound
```

Use a stable, indexed primary-key order. Do not use offsets: concurrent writes
can move offset windows and cause skipped or repeated rows. Rows created above
the captured upper bound belong to a later run or normal dual-write behavior.

`processNextBatch` updates eligible rows and advances the checkpoint atomically
in one short transaction. It must not hold a table-level lock or keep a
transaction open while waiting for the next batch.

## Make Each Row Idempotent

The row update must converge on the same state when retried. Guard it with a
predicate that selects only rows still needing the transformation, or use an
equivalent compare-and-set condition. Do not increment, append, emit an event,
or call an external service unless duplicate execution is independently
deduplicated.

Record both the selected-row count and changed-row count. A crash after commit
but before the caller observes completion must be harmless on restart.

## Persist and Resume

`BackfillRepository.loadOrCreate` creates or loads the checkpoint.
`processNextBatch` returns the next committed checkpoint. An empty batch is
complete only when `hasEligibleRowsAtOrBelow` confirms no eligible row remains;
otherwise the harness fails instead of silently skipping work.

Operational stop conditions return `PAUSED` and preserve the last committed
cursor. Restart the same operation and definition to resume. Never reset the
cursor to accelerate recovery.

## Throttle and Pause

Set an explicit positive `rowsPerSecond`. `RateLimitedBackfillThrottle` waits
after each committed batch, based on the number of selected rows. Select batch
size and rate from staging evidence, database headroom, replica lag, and exam
traffic rather than from wall-clock completion targets.

Implement `BackfillRunPermit` so an operator, open-session policy, or resource
signal can pause before the next batch. A pause must not interrupt the current
short transaction.

## Run in the Worker Role

Enable the `worker` Spring profile and name exactly one registered operation:

```bash
SPRING_PROFILES_ACTIVE=worker \
CBT_BACKFILL_OPERATION='answer-normalization:1' \
./gradlew bootRun
```

Supply the worker workload's normal R2DBC configuration and least-privilege
credentials through the deployment secret mechanism. Do not provide
`app_migrator` credentials. The backfill uses reactive persistence APIs and
must not call `block()` or perform JDBC work on request/event-loop threads.

The backfill is asynchronous operational work: release success does not wait
for it to finish. `MIGRATE` code must tolerate partially populated data until
the operation is complete and verified.

## Verify Before Contract

Tests and staging evidence must demonstrate:

- interruption after a committed batch resumes from the persisted cursor;
- retrying any batch produces no duplicate effect;
- the configured rate and pause permit are honored;
- no table-level lock is held and transactions remain batch-bounded;
- completion includes a no-eligible-rows check; and
- the old representation remains available until a later `CONTRACT` release.

Do not schedule contraction until the backfill completion evidence and N+1
dual-write observation are both retained.
