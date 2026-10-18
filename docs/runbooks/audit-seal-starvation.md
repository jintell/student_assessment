# Audit epoch-seal CAS starvation

Trigger: retries sustained above 5/min for one tenant, or one seal retrying
more than 10 times. Severity P2. Owner: Audit with runtime/database Operations.
The alert owner must specify the sustained window; do not silently invent or
weaken it. Brief CAS losses are normal when several retention classes close.

## Establish the cause

Record tenant/epoch, deployment, scheduler ownership, attempt/correlation
references and age of the oldest unsealed epoch in restricted evidence.
Check whether measurements are live: process retry counters reset on restart
and do not identify a tenant or span repeated deferred job invocations.
Use protected epoch attempt history to establish the threshold; do not add
tenant/shard labels or sum duplicate inventory gauges.

Inspect canonical close ordering: ascending UTC period, then the approved
retention-class order, one seal transaction at a time. Verify all workers
select that same next eligible epoch. Persistent contention suggests ordering
is not being applied, duplicate schedulers, excessive overlapping retries or
another lifecycle/locking problem; confirm with evidence before changing it.
Check distributed singleton ownership and lost-lock/shutdown handling. A
one-replica template and CAS correctness are not scheduler exclusion proof.

Distinguish CAS loss from KMS/signature/database unavailability. KMS failure
defers sealing and raises its operational signal; it must not block business
writes or be mislabeled as a successful close. An unsealed epoch **blocks
disposition**, even after its retention due date.

## Recover without changing history

Restore the approved singleton and canonical selection path. Bound retries;
after losing CAS, roll back, read the new head and re-derive/sign the candidate
against it. Never reuse the losing candidate, force root_seq, insert a sibling
root, take an unapproved lock or clear seals to get progress. Do not increase
replicas/pools or retry indefinitely as a contention remedy.

Use the approved close operation to resume after the cause is fixed. Verify a
dense unique root sequence, correct signatures and progress of the oldest
unsealed epoch before allowing normal disposition. A fork, sequence gap or
invalid signature becomes P1: follow [chain break](audit-chain-break.md).
Retain before/after attempts, lock ownership and verification evidence.

P8.8/P9.3 runtime/alert wiring remains open; the current telemetry adapter does
not create a scheduler or a per-epoch retry history. Missing observations are
an instrumentation gap, not proof that starvation is absent.
