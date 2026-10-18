# Audit verification model

Verification establishes consistency against expected inventory and trusted
signed commitments, not merely that a query returned no errors. Evidence,
historical codec versions and public signing-key versions must remain readable.
Operational logs and a process-local success gauge are not verification proof.

## Daily open-chain verification

The daily obligation is to walk open chains, verify predecessor hashes and
dense sequences against committed heads, and verify checkpoint prefixes and
signatures. `AuditDailyVerifier` currently walks the supplied open-chain records
from the seed, compares checkpoint ends with walked prefixes, and verifies
checkpoint signatures. It also re-derives supplied sealed epoch roots and
verifies their signatures. This is not a replacement for a full retained-history
and tenant-root-order walk.

Approved checkpoint policy is 10,000 uncheckpointed records or one hour,
whichever comes first, plus a terminal checkpoint for each non-empty closing
shard. Empty shards use their seal sentinel. These are operating requirements;
the production checkpoint scheduler and providers still need wiring. A checkpoint
is a signed prefix, not permission to rewrite earlier records.

The daily service returns open-chain/sealed-epoch counts or an error. It does
not itself invoke the full-verifier preservation/P1 handler. Stage-8
PostgreSQL evidence is a test, not proof of a daily production job or exercised
alert. There is no approved dedicated ARC-VERIFY identifier for this daily limb
([DEFECT-002](defects/TASK-AUD1-DEFECT-002.md)); do not invent one.

## Quarterly and post-restore full re-walk

Invoke `AuditFullVerifier.verify(QUARTERLY)` for quarterly review and
`verify(POST_RESTORE)` after restore. Walk every supplied retained chain from
its seed, verify checkpoint prefixes/signatures, and validate every tenant's
ordered root chain and seal signatures against its committed root head. Include
retained anchors for disposed epochs; do not expect deleted payloads to be
available or remove their roots to make the walk contiguous.

For each run retain trigger, run/restore identity, environment/release/schema /
codec/key versions, expected and observed tenant/epoch/shard coverage, start/end
times, terminal outcome and protected findings references. Compare the complete
expected inventory with observed coverage. Empty or incomplete evidence
providers can return zero counts successfully; that must not certify an
unexpectedly empty database. Cancellation, timeout, missing keys or unsupported
versions also block acceptance, even when not evidence of tampering.

After restore keep writes, sealing and disposition gated until verification
and the owning recovery criteria succeed. FEAT-OPS-003 owns release of that
gate and the real rehearsal; see the
[restore handover](evidence/FEAT-AUD-001/P8.7-post-restore-verification-handover.md).

## Preserve, never repair

**Preserve evidence and escalate P1 before attempting recovery.** Retain original
records, heads, checkpoints, seals, signatures, key references and restricted
findings. Halt affected sealing/disposition through approved incident controls.
Never rewrite a hash, reset a seed, delete a sibling root, choose a preferred
fork, re-sign history or run corrective SQL to make verification green.

For a classified mismatch the full-verifier ports capture findings and order
preservation, high-severity metric, P1 alert and halt before propagating failure.
Tests establish that ordering, but production preservation/alert/halt adapters,
daily failure integration, typed fork detection and scheduling remain absent.
Not every generic root mismatch is a fork. Use the
[chain-break runbook](runbooks/audit-chain-break.md), retain failed reports as
well as successful ones, and require authorised recovery plus a fresh complete
verification before releasing containment. No clean-chain claim follows from
an initial zero counter or unsampled/NaN gauge.

## Retained evidence

[P7 verification](evidence/FEAT-AUD-001/phase-7-testing.md),
[disposition verification](evidence/FEAT-AUD-001/phase-7-scope-disposition-testing.md)
and [P9 execution status](evidence/FEAT-AUD-001/P9.1-P9.11-execution-summary.md)
separate passing unit/PostgreSQL tests from unimplemented live operations.
The daily verification, quarterly review and post-restore obligations all remain
operational requirements until providers, coverage checks, schedules and incident
routing are deployed and exercised.
