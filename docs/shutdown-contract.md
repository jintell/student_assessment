# Shutdown Contract

This normative FEAT-PLAT-006 P10.3 contract applies to later endpoints, consumers
and sweeps. A work unit must be interruptible at a transaction boundary.
Completion means the transaction committed or rolled back and its owned
resources were cleaned up. A lifecycle callback must never report completion
while detached work continues.

## Order

`SmartLifecycle` stops higher phases first. Relative to
`WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE` (`G`):

| Order | Phase | Required behavior |
|---|---|---|
| Before SIGTERM | Kubernetes preStop | Create `/run/cbt/draining`, stop admission/readiness, allow routing convergence for 30 s |
| 1 | `G + 1`, `SweepRunner` | Stop sweep interval sources, discard pending ticks, finish the current bounded batch at its transaction boundary; start no new batch |
| 2 | `G`, web server | Finish admitted HTTP requests under `server.shutdown=graceful` |
| 3 | `G - 1`, `BrokerDrainConfiguration` | Stop broker prefetch, finish and acknowledge in-flight committed work; unfinished deliveries remain recoverable |
| 4 | `G - 2`, `SweepLockShutdown` | Release successfully drained sweep lock handles last, await tick cleanup, then dispose the dedicated lock pool |

Failed/cancelled ticks still clean up immediately; deferring a normal drained
handle does not excuse leaked failed sessions. Do not close lock handles or
dispose their pool before their protected work is safe. Business recovery must
be durable and idempotent; a later replica cannot rely on the stopped pod's memory.

## Budget

The deployable contract is `deploy/runtime/values.yaml`, with the matching
`application.yaml` lifecycle setting: `terminationGracePeriodSeconds=60`,
`preStop=30 s`, and `spring.lifecycle.timeout-per-shutdown-phase=5s`.
Budget all four waiting phases: `30 + (4 × 5) + 5 cleanup + 5 margin = 60 s`.
The early task-list proposal of 25 s **per phase** does not fit and is superseded
by the implemented five-second budget. Sweep batch duration is at most four
seconds; the longer tick bound does not allow another batch during drain.
Reactor resource shutdown uses zero quiet period and a one-second timeout.

New lifecycle participants must fit this total, not silently add another full
waiting phase. Any proposed timeout or preStop change requires recomputing the
total, reviewing deployment values and rerunning the shutdown tests. Do not extend
grace to hide unbounded queries or an uninterruptible batch.

## Recovery and Verification

On cancellation or timeout, roll back incomplete work and release its resources.
Broker acknowledgement follows durable completion; retries must not duplicate
effects. Sweeps check the stop signal before opening the next transaction and
resume from durable progress. Do not block event-loop threads or create detached
subscriptions in work units.

Verify `GracefulWebShutdownTest`, `SweepLockShutdownTest`,
`BrokerDrainConfigurationTest`, `ReferenceSweepIntegrationTest` and
`SweepSchedulingIntegrationTest`. Retain evidence for in-flight commit,
timeout/rollback, no new admission, recoverable restart and release ordering.
These local tests do not discharge a live staging termination drill.

Use [the shutdown runbook](runbooks/graceful-shutdown.md) for operational response.
`shutdown_phase_duration_seconds{phase="sweep"}` measures the implemented sweep
drain; `shutdown_abandoned_units_total{phase="sweep"}` counts discarded queued
ticks, not lost committed business work. Do not infer telemetry for all four
phases from the sweep metric alone.
