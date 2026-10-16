# Graceful Shutdown

Use this runbook for `RuntimeGracePeriodExceeded`, elevated
`shutdown_phase_duration_seconds`, non-zero
`shutdown_abandoned_units_total`, or pods stuck terminating.

## Read the Signals

1. Identify the runtime role, pod, image digest, shutdown start, Kubernetes
   termination reason, and configured grace period.
2. Graph `shutdown_phase_duration_seconds` by `phase` for the pod lifetime.
   The runtime-owned `sweep` phase stops tick sources and waits for the active
   batch transaction boundary before discarding queued tick signals.
3. Check `shutdown_abandoned_units_total{phase="sweep"}`. A positive value is
   queued ticks discarded during drain; it is observable abandoned scheduling
   work, not a missing log inference. The next replica may execute a later tick.
4. Correlate sweep outcome/duration, broker in-flight deliveries, database
   transactions, pool state, and the pod termination event. A pod killed at the
   deadline may not export its final metric, so Kubernetes state is decisive.

## Current Budget

`deploy/runtime/values.yaml` allocates 60 seconds: 30 seconds preStop, four
five-second lifecycle phases, five seconds other cleanup, and five seconds
margin. Spring's per-phase timeout is five seconds. Lock connections release
after the in-flight sweep boundary and before the dedicated pool is disposed.

## Decide the Change Type

### Manifest Change

Change deployment values only when measured, healthy shutdown work has a
stable duration that cannot fit the approved budget because of environment or
orchestrator timing. Examples are an approved preStop routing delay or a
re-derived termination grace/per-phase timeout. Recompute the entire budget,
retain margin, test the live manifest, and update every role consistently.

Do not extend grace to hide an unbounded query, non-interruptible batch, stuck
broker acknowledgement, or missing lifecycle callback.

### Code Change

Change code when a phase fails to stop admitting work, ignores the stop signal,
crosses no bounded transaction/batch boundary, leaks a subscription or lock,
waits on an unbounded external call, disposes the pool before releasing locks,
or reports completion while work remains. Preserve idempotency and recovery;
never force a partial commit to meet the timer.

## Immediate Recovery

- If shutdown is progressing and remains inside the approved budget, allow it
  to finish.
- If the deadline killed the pod, confirm the database transaction rolled back
  or committed atomically, verify the advisory lock released, and ensure one
  replacement replica resumes ticks.
- Roll back the image for a repeatable code regression. Do not roll back schema.
- Escalate repeated grace exhaustion or any double-applied/lost unit as a
  reliability incident.

Close the incident only after the replacement is ready, phase duration returns
inside budget with margin, abandoned-unit count is understood, locks are clear,
and no duplicate business effect occurred.
