# Authoring a Sweep

This is the FEAT-PLAT-006 P10.1 contract for later sweep-owning features.
Keep the adapter in the owning feature's `infra` package and its business
policy inside that feature. Depend on `platform.api` contracts; do not call
runtime implementation classes or add shared-kernel scheduling types.

## Registration and Identity

1. Implement `SweepDefinition` and register the bean with exactly one direct
   `@RoleScoped("worker")` declaration, on the type or its factory method.
2. Return immutable `SweepMetadata`: stable name, module, feature, approved
   `SystemActor`, default/minimum/maximum interval and maximum batch/tick duration.
3. Match the reserved row in `platform.sweep_registry`. The six reserved names
   and stored keys are in `V10__seed_sweep_registry.sql`. Derive nothing in the
   sweep: no hash, advisory-lock key, scheduling identity or local interval.
   V12 additionally reserves the disabled `outbox-backlog` collector; its candidate
   timing and enablement review are documented in [outbox collection](outbox-backlog-collection.md).
   A new name needs a reviewed additive platform migration with a unique stored
   key, ownership and actor; never rewrite an applied migration.
4. Keep the row disabled until its implementation and operating prerequisites
   exist. Enabling requires a reviewed additive migration while the audited
   operator command is blocked; see [registry changes](runbooks/sweep-registry.md).
   The registry is loaded at worker startup, not polled for live changes.
5. Review the worker manifest in `contracts/runtime-roles/worker.json` with
   the feature change. The runner owns intervals, lock acquisition and permits:
   one sweep lane plus a separate relay lane, at most two lock connections.

## Context and Batches

The runner installs the declared platform-system `ActorContext` and
`SweepMetadata` in Reactor context. Use `SweepTransactions.inPlatformTransaction`
with the declared `SweepDataModule` to install the approved module role and
enumerated platform-scope marker before business SQL. Use
`inTenantTransaction` for tenant work; the adapter installs tenant scope and
attribution. Do not issue manual `SET ROLE`, forge context, use an unwrapped
connection, nest transactions or treat the worker login as a business role.
Actor/module combinations are restricted by `DefaultSweepTransactions`; a new
combination needs an explicit reviewed security change, not a fabricated actor.

Return deferred `Mono<SweepBatchResult>` work for one bounded transaction.
Check `SweepStopSignal.isStopRequested()` before starting; a stop during work
allows the current transaction to finish and prevents another batch. Completion
must include commit or rollback. Cancellation must roll back unfinished work
and release resources. Persist progress in the owning schema so retries recover
from committed state. Do not keep a cursor in memory, detach subscriptions,
call `block()`, or use blocking JDBC on the reactive path. Use the injected
`Clock` for business time and append required audit events in the same transaction.
A work unit must be interruptible at a transaction boundary.

## Rejected at Startup

`SweepRegistryLoader` fails worker boot for an unavailable registry, missing
row for a definition, duplicate definitions/names, colliding stored keys,
unknown actor/module, malformed identity, or an enabled row without a bean.
Disabled reservations without implementations are allowed. Declaration and row
must agree on module, feature, actor and default interval, even when disabled.
Intervals must fit declared bounds and the runtime's 100 ms–24 h range;
auto-submit remains 10 s, stale-claim-reclaim 30 s and outbox-relay 200 ms.
Maximum batch duration is 4 s and maximum tick duration is 300 s. Invalid
metadata fails construction; invalid role declarations or manifests fail their
own validators. Nothing silently clamps an invalid value.

## Evidence Before Enablement

Use the [test-only reference sweep](reference-sweep.md) as the executable
example, including its secured PostgreSQL tests. Keep fixtures out of production
manifests and migrations. Cover commit, rollback/cancellation, stop between
batches, durable restart, missing/incorrect context, and N-replica lock contention
for both lock adapters. Assert no leaked connections and no overlapping body.
Run the owning feature tests plus `conformanceTest` and the runtime integration
tests. Consult [shutdown](runbooks/graceful-shutdown.md) and
[stalled sweeps](runbooks/sweep-stalled.md) before shipping.
