# Migration Lock-Duration Thresholds

Status: approved

Approval record: `ci/dor/P0.3-lock-duration-thresholds.json`

Runtime/CI configuration: `config/lock-duration-thresholds.yml`

Architecture requires stage 12 to block excessive migration locks but did not
specify the bound. `TASK-PLAT5-DEFECT-001` proposed a three-level policy, and
Platform Ops and the Engineering Lead approved it on 2026-09-14.

## Approved Values

| Relation class | Level | Hold duration | Result |
|---|---|---:|---|
| Exam-critical | Warning | 100 ms | Emit a warning and retain the exact statement, relation, and lock mode. |
| Exam-critical | Failure | 250 ms | Fail stage 12 when the measured hold is greater than or equal to the bound. |
| Non-critical | Failure | 2,000 ms | Fail stage 12 when the measured hold is greater than or equal to the bound. |

Exam-critical relations are declared in
`migration/exam-critical-tables.yaml`. The initial set is
`delivery.answer`, `delivery.answer_operation`, `delivery.attempt`, and
`audit.audit_event`.

`ShareUpdateExclusiveLock` from an allowed concurrent-index operation is
classified as `ONLINE_COMPATIBLE`, not as a blocking failure. Stage 12 still
records it. This distinction keeps a long concurrent build from being confused
with a long write-blocking lock.

## Rationale

The values were selected as an explicit availability policy, not inferred from
an absent production baseline:

- 100 ms gives an early signal on the exam path before a blocking hold reaches
  the release-failure boundary.
- 250 ms keeps a routine DDL lock short relative to candidate answer and
  attempt traffic, where a release-induced stall threatens `NFR-AVAIL-001`.
- 2 seconds gives non-critical relations a wider bounded window without making
  an unbounded or unexpectedly rewriting statement acceptable.
- Separate relation classes preserve strict protection for exam traffic while
  avoiding a single global value that would create false failures for valid
  online operations.

The 10 ms lock sampler and per-relation/per-mode report make these holds
observable at the chosen scale. The approved values are policy bounds, not a
claim about a measured production latency percentile. Staging and production
evidence may justify a reviewed change, but code authors may not tune the
threshold around a failing migration.

## Enforcement

`ci/verify-lock-threshold-approval` fails unless:

- all three configured values equal the approved record;
- the approval is `APPROVED` and names `TASK-PLAT5-DEFECT-001`;
- both detached signatures validate against trusted keys;
- Platform Ops and Engineering Lead are distinct signers; and
- configuration and approval values agree.

Stage 12 then applies the values to measured lock holds and retains the value,
source checksum, measurement, and verdict in the lock-duration report.

## Review and Change Path

Any change requires all of the following in one reviewed change:

1. Evidence from a production-shaped stage-12 run or a production incident,
   including the statement, relation, lock mode, measured holds, sampling
   interval, and exam-path impact.
2. An updated rationale and risk assessment against `NFR-AVAIL-001`,
   `ARC-RISK-017`, and the deploy-freeze behavior in `ARC-OPS-013`.
3. Updated `config/lock-duration-thresholds.yml`, policy tests, verifier
   expectations, runbooks, alerts, and report fixtures.
4. A new immutable approval record signed independently by Platform Ops and
   the Engineering Lead.
5. A green `verifyLockThresholdApproval` and full CI stage 12 run.

Lowering or raising a value by pipeline parameter, environment variable, or
unsigned configuration is prohibited. Emergency response uses the deploy
freeze and forward-fix procedures; it does not waive the threshold.
