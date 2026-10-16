# Relay Adoption of the Runtime Lock Port

FEAT-PLAT-006 P10.5 publishes the closure of TASK-PLAT4-DEFECT-004.
The implementation seam closed on 2026-10-14; this document makes its evidence
discoverable for the Phase 0 feature owners.

| Task | Before / resulting responsibility |
|---|---|
| FEAT-PLAT-004 P4.6 | Originally delivered relay-local advisory-lock acquisition and key derivation pending the runtime registry |
| FEAT-PLAT-004 P7.17 | Replaced that acquisition with the common `SweepLock` port and reran relay singleton evidence |
| FEAT-PLAT-006 P4.25 / P7.19 | Retired local lock SQL and derivation; enforced the sole acquisition boundary and proved common runner contention |
| FEAT-PLAT-006 P8.9 | Retained sibling-suite and persistence-isolation regression evidence after adoption |

`platform.infra.outbox.RelaySingleton` now acquires a `SweepLock.Handle` by
sweep name. The selected runtime adapter reads the persisted `outbox-relay`
key from `platform.sweep_registry`; no caller derives a replacement key.
`Mono.usingWhen` closes the handle on completion, failure and cancellation.
The common ownership monitor checks during unfinished work as well as around
the body, cancelling work when ownership is lost. Acquisition remains deferred
until subscription and a losing contender skips work.

There is one lock port with two selectable adapters, not two independent relay
lock mechanisms. Runtime conformance rejects advisory-lock acquisition outside
those adapters and rejects independent scheduling sources. Session and
transaction adapters share the stored key and lock namespace. The mechanism
provides mutual exclusion while a lock is held; it is not a durable once-per-tick
claim or a fencing token for a separate business transaction.

This closure does not mean a production relay scheduler is enabled: the reserved
row remains disabled and the worker manifest has no production sweep entries.
The adapter adoption and its tests are delivered independently of that future
enablement. No individual business sweep is implemented by this document.

## Retained Evidence

- [Containment and adoption closure](evidence/FEAT-PLAT-006/P4.25-containment-closure.md):
  `RelaySingletonIntegrationTest` tests contention and backend-loss cancellation,
  rollback and replacement publication; `SweepRunnerContentionIntegrationTest`
  tests four runners across four ticks for both adapters.
- [Sibling-suite regression](evidence/FEAT-PLAT-006/P8.9-sibling-suite-regression.md):
  runtime P8.9 and ARC-VERIFY-024 evidence.
- [Outbox task list](../tasks/foundation/outbox/tasks.md): P4.6 and P7.17.
- [Runtime task list](../tasks/foundation/runtime/tasks.md): P7.19 and P10.5.

Resume any later relay change against this port and the
[sweep-authoring contract](sweep-authoring.md), not the historical local-lock design.
