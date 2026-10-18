# Audit chain-head contention

Trigger: measured predecessor-lock wait p95 >50 ms over 10 minutes. Severity
P2. Owner: Audit with database/runtime Operations and the affected delivery
feature. Read shard skew **before answer latency breaches**; compare answer
p95 against its one-second objective when the real answer route is available.

## Verify the observation

Record deployment, time window, affected tenant/epoch and protected shard
exemplar references. Verify the sampler covers the actual write mix and the
same observation window. Lock acquisition query time includes network/service
time; it is not automatically lock wait. Missing, stale or undefined skew is
unknown, not balanced traffic. P9.2 has not yet delivered this measured source
and protected exemplar path; use approved database diagnostics until it exists.

Compare maximum and median records across all configured shards, including
empty shards. A zero median needs explicit sampler semantics; do not divide
by zero, drop empty shards or hide a hot tenant in a global average. Inspect
the representative entity distribution without exporting personal payloads.

Treat a hot shard as evidence to investigate the shard function or an unsuitable
N for this tenant's write mix: verify the stable entity-ID hash, fixed policy
version, provisioned heads and actual N. Approved default is 64. Repeated
writes to one entity naturally serialize and must not be spread arbitrarily
to disguise contention. Also inspect long transactions, pool acquisition,
late business SQL and unexpected lock holders separately from skew.

## Safe response

Restore the correct canonical assignment and short approved transaction path;
preserve predecessor-lock then atomic event-insert/head-advance ordering and
the two-statement audit budget. Never bypass the lock, emit asynchronously,
drop audit events, remap existing evidence, or weaken request security.

If measured load requires a new N, use FEAT-TENANT-001's approved, audited
epoch-boundary policy-change workflow after current epochs close and before
the target epoch opens. Do not edit N in place or change the signed default
without its required approvals. Respect connection/capacity limits; extra
writers against one hot head can worsen waiting.

Close with the same-window before/after wait percentiles, skew, request latency,
query count and clean chain/root verification. Require real measurements; an
unimplemented answer route or unknown series cannot certify recovery or A6.
Escalate an integrity mismatch using [chain break](audit-chain-break.md).
