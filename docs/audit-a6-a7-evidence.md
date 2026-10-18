# A6/A7 evidence: Phase 6 gates, not Phase 0 discharge

Baseline issue: `TASK-AUD1-DEFECT-005`. FEAT-AUD-001 supplies the foundation,
fixture/protocol design and non-load integration evidence. The approved full
profile and production-shaped lifecycle evidence are Phase 6 obligations;
marking foundation tasks complete cannot discharge either condition.

Plan §14.5 states the A6 warning in full:

> **ARC-VERIFY-031** — "a run that meets the latency target without recording
> contention does not discharge this condition." Lock wait, shard skew and
> audit share of transaction time must be recorded.

In particular, **a run that meets the latency target without recording
contention does not discharge A6**. No metric placeholder, absent series,
synthetic zero or overall query duration may stand in for measured lock wait.

## A6 — ARC-VERIFY-031

Run one tenant at the approved ceiling: the [P2.19 harness specification](evidence/FEAT-AUD-001/phase-2-architecture-design.md#p219-a6a7-verification-harness)
uses 64 shards, ten simultaneous sessions, up to 50,000 candidates and about
1,110 answer saves/second, with navigation, entry, submission and administrative
audits included. Pin the approved run profile rather than selecting a smaller
load to obtain green latency. Assert answer-acceptance p95 ≤ 1 second and the
real six-statement route budget, of which exactly two statements are audit.

Force concurrent multi-class epoch close while writes continue. Use barriers
to produce an actual CAS loser; accidental serial execution is not concurrency
evidence. Require loser rollback, fresh read/derivation/signature, one seal per
epoch, dense duplicate-free root sequence, exact predecessors and no sibling
root. Kill the sealer after signing but before seal/head commit, both with and
without a competitor; restart must re-read/re-sign and commit exactly once.
Assert no committed unattributed business change, orphan event or head and
zero breaks in the complete post-run verification.

Record offered/completed rates and status outcomes, answer latency p50/p95/p99,
true predecessor-lock wait distributions, per-shard counts and skew including
empty shards, audit time and total transaction time, exact query counts,
rollback/cancellation outcomes, CAS retries, signing latency/errors and resource
headroom. Retained protected per-shard evidence is not permission to publish
tenant × shard metric labels. The cardinality-safe sampler/exemplar contract
and zero-median semantics must be resolved with FEAT-OBS-001.

## A7 — ARC-VERIFY-032

Seed all four classes across two UTC month boundaries and multiple shards,
overlapping retention obligations under two append-only policy versions, and a
hold affecting only part of a physical leaf. Verify longest-wins placement and
stored policy evidence. Dispose an expired unheld leaf while another class's
seal is deliberately in flight, testing barriers before signing, after signing
and before root CAS with fresh fixtures rather than timing sleeps.

Require the [five disposition steps](audit-disposition.md) in order, including
committed/verified disposition evidence before detach and retained-tail/root
verification afterward. The held leaf remains attached with one audited
suppression, and authorised release preserves its original clocks. All tenants
in the physical leaf must be covered. Concurrent sealing must not be lost,
duplicated or create a root gap. Retain outcomes, exact leaf identities,
policy/hold references, sequence/root/signature evidence and post-run reports.

## Evidence bundle and acceptance

Retain source commit, deployment/container digests, migration/schema versions,
PostgreSQL version/settings, shard topology, deterministic seed and synthetic
fixture manifest, UTC boundaries, offered workload, barriers/fault schedule,
signer/key versions, policy versions, raw measurements and complete verifier
reports. Include required-versus-observed coverage, checksums/artifact locations,
run times, owners and verdict. Missing required measurements or incomplete
inventory invalidate the run; preserve failed runs too. Phase 6 must use
production-shaped staging and real workload identities/KMS, not a fake signer.

Audit owns chain/CAS evidence, FEAT-DLV-002 the answer route, FEAT-OBS-001 the
measurement contract, FEAT-PRIV-001 policy/hold execution and FEAT-OPS-005 the
full-profile run. Architecture acceptance must distinguish passing components
from discharge of the complete conditions.

Current blockers: the real answer-save route, measured P7.24 load harness,
contention sampler/export integration and production lifecycle wiring are not
delivered. See [P7.26 retained results](evidence/FEAT-AUD-001/P7.26-retained-results.json),
[P9.2 measurement blocker](evidence/FEAT-AUD-001/P9.2-contention-instrumentation-blocker.md)
and the [deferral register](evidence/FEAT-AUD-001/P8.10-deferral-register.md).
Existing PostgreSQL CAS/disposition fixtures are valuable non-load evidence,
not A6/A7 discharge or proof that the full harness has shipped.
