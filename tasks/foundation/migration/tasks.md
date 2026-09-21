# Task List — `FEAT-PLAT-005` Expand/Contract Migration Pipeline

## Overview

|                       |                                                                                                                                                                                                                                            |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source plan           | `../../../plan/plan.md` §8.1 (`FEAT-PLAT-005`), §8.0 (universal DoR/DoD), §10 Phase 0, §11.2 track (a), §15.1                                                                                                                              |
| Architecture baseline | `../../../architecture.md` v1.4 at tag `arch-v1.4` — §9.8 (`ARC-PLAT-005/007/009`), §15.2, §16.2, §16.4, §17.1, §17.6 (`ARC-OPS-005…007`), §18.1 stages 12/15/19/20, §18.2 (`ARC-OPS-008`), §19.9, §20.5 (`ARC-OPS-013`), §22.2, §24.3, `ADR-019` |
| Delivery phase        | Phase 0 — Engineering Foundation                                                                                                                                                                                                           |
| Dependencies          | `FEAT-PLAT-002` (migrator role, schemas, Flyway entrypoint, per-module locations, pinned PostgreSQL). Transitively `FEAT-PLAT-001` (build and CI entry points)                                                                              |
| Consumed by           | `FEAT-OPS-007` (deployment, canary, rollback), and every feature that ships a migration — the universal DoD makes CI stage 12 a gate on all of them                                                                                          |
| Generated on          | 2026-09-02                                                                                                                                                                                                                                 |
| Methodology           | Clean architecture. The migration pipeline is **infrastructure and build tooling**, never domain code: the forbidden-operation checker and dataset generator live in a standalone Gradle module with no dependency on application code, and the deploy-freeze check depends on a `SessionWindowQuery` **port** rather than on the scheduling module |
| Granularity           | One objective per task, independently verifiable, implementable by one engineer or agent in under a day                                                                                                                                     |
| Task reference key    | `P<phase>.<number>` — e.g. `P4.12` is Phase 4 task 12                                                                                                                                                                                      |
| Marker convention     | `[ ]` open, `[*]` complete                                                                                                                                                                                                                 |

**Objective.** Make schema migration an expand-then-contract discipline that is *mechanically* enforced and
measured against production-shaped data, so a release can be deployed and rolled back without a lock that
stalls the exam path.

**Why this feature carries weight beyond its size.** `ARC-OPS-006` states that code-only rollback is the
single most important reason expand/contract is mandatory rather than preferred: a schema rollback under load
on a table holding accepted answers is not a recoverable operation. Every rollback bound in §18.2 and every
canary path in §17.6 rests on version *N−1* running correctly against version *N*'s schema. If that property
is a convention rather than a gate, it will be violated by an ordinary release, and `ARC-RISK-017` — High
impact — becomes an availability event caused by routine work.

### Confirmed implementation decisions

| Decision                       | Choice                                                                                                                                                                                                                                              | Consequence                                                                                                                                                     |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Lock-duration enforcement      | **Two limbs.** Preventive: every migration session sets `lock_timeout` to the fail threshold, so a lock that cannot be acquired fails fast instead of queueing ahead of exam traffic. Detective: CI 12 measures actual hold duration and fails beyond threshold | A migration cannot stall the exam path even if the CI measurement is somehow unrepresentative. The gate proves the bound; the timeout enforces it in production |
| Threshold values               | **100 ms warn / 250 ms fail** on any lock touching an exam-critical table; **2 s fail** elsewhere                                                                                                                                                    | `PLAN-RECOMMENDATION` — no numbered requirement states a value. See `TASK-PLAT5-DEFECT-001`                                                                      |
| Forbidden-operation detection  | **Allowlist of permitted DDL shapes**, parsed from the script's SQL, not a regex blocklist                                                                                                                                                           | An unrecognised DDL shape fails **closed**. A blocklist passes every operation nobody thought to forbid                                                          |
| Production-shaped dataset      | Generated deterministically from a declarative volumetric profile derived from §15.2 and a fixed seed. **Never a production dump**                                                                                                                   | Regenerable from source, diffable, and carries no personal data — so CI never becomes a processing location under `REQ-PRIV-*`                                   |
| Phase classification           | Every migration script carries a **mandatory** `-- cbt:phase EXPAND \| MIGRATE \| CONTRACT` header, machine-checked                                                                                                                                        | CI can refuse an `EXPAND`+`CONTRACT` mix in one release and refuse a rollback of a `CONTRACT` release (`ARC-OPS-008`) without human judgement                    |
| N−1-against-N compatibility    | The previous release's **retained image digest** is run against the new release's migrated schema in CI 12                                                                                                                                            | `ARC-OPS-006` is proven per release, not inferred from the discipline. This is what makes every §18.2 rollback bound real                                        |
| Deploy freeze                  | A **fail-closed** precondition behind a port: an unknown or unreachable session-window source refuses the deploy                                                                                                                                      | Phase 0 has no session data yet, so the default answer must be "refuse", never "proceed" (`ARC-OPS-013`)                                                         |
| Backfill                       | Owned here as a **harness** (batched, throttled, resumable, off-peak) run in the `worker` role — **not** in the migration Job                                                                                                                        | The Job must complete before any pod serves traffic; a throttled backfill cannot. See `TASK-PLAT5-DEFECT-004`                                                    |

### Assumptions

1. **No business tables exist in Phase 0.** The deliverable is the pipeline. The volumetric profile carries a
   zero row for every table not yet defined, and each owning feature adds its own row when it ships tables.
   Conformance is proven now against `platform.tenant_scope_probe` (`tasks.md` `P3.13`) plus a dedicated
   `platform.migration_fixture` table created and dropped by the suite.
2. **Flyway, the `--migrate-only` entrypoint, the per-module locations and the `app_migrator` role already
   exist** (`tasks.md` `P3.4`, `P3.5`, `P3.12`). This feature adds classification, gates, measurement and
   rollback proof; it does not re-create the entrypoint.
3. **Exam-critical tables are schema-qualified** as `delivery.answer`, `delivery.answer_operation`,
   `delivery.attempt` and `audit.audit_event` per §9.2, superseding §9.8's unqualified spelling, and the rule is
   generalised to any table on the exam-critical path. Recorded as `TASK-PLAT5-DEFECT-003`.
4. **The `NFR-REL-004` limb owned here** is *a failed migration blocks the release rather than half-applying*.
   Graceful shutdown sequencing (`ARC-OPS-007`) belongs to `FEAT-PLAT-006`.
5. **`ARC-OPS-013` is enforced at CI stages 19 and 20.** This feature owns the precondition check and its
   fail-closed default; the deployment orchestration that consults it is `FEAT-OPS-007`.
6. **PostgreSQL 17, pinned by digest** (`tasks.md` `P3.1`). `CREATE INDEX CONCURRENTLY`, `lock_timeout`
   and `pg_locks` semantics are all version-sensitive and are assumed at that version.
7. **`CREATE INDEX CONCURRENTLY` cannot run inside a transaction**, so scripts using it must be marked
   non-transactional, and a failed build leaves an `INVALID` index requiring `DROP INDEX CONCURRENTLY` before
   retry. This is an operational hazard the pipeline must handle, not a footnote.

### Blockers and defects carried into this task list

| ID                      | Statement                                                                                                                                                                                                                                                             | Owning task      |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| `PLAN-BLOCKER-001`      | `ci/architecture-ratification.json` is `status: RATIFIED`. Per plan §10 Phase 0 entry criteria this gates Phase 0 **implementation**. Discharged by `tasks.md` `P0.1`–`P0.7`; not restated here                                                                   | `P0.1`           |
| `TASK-PLAT5-DEFECT-001` | CI stage 12 is **BLOCKING** on "a lock held beyond threshold", but no threshold value exists in requirements, architecture or plan. A blocking gate with an undefined bound cannot be implemented. 100 ms / 250 ms / 2 s proposed as a `PLAN-RECOMMENDATION`             | `P0.3`, `P10.8`  |
| `TASK-PLAT5-DEFECT-002` | §19.9 registers a "Migration lock-duration report" produced by CI 12 and consumed by Change advisory, yet no `ARC-VERIFY-###` identifier owns migration verification. No new identifier is invented here; the gap is raised for the next baseline                       | `P1.6`, `P10.8`  |
| `TASK-PLAT5-DEFECT-003` | §9.8 and `ADR-019` name `answer`, `attempt` and `audit_event` unqualified; §9.2 schema-qualifies them. Qualified names adopted and the rule generalised to the exam-critical path                                                                                       | `P1.2`, `P10.8`  |
| `TASK-PLAT5-DEFECT-004` | §9.8 places a throttled, resumable backfill in release N, but the §17.6 release sequence has no backfill step and the migration Job must complete before pods serve traffic. Resolution: backfill is a `worker`-role job with its own harness, never the Job             | `P2.9`, `P4.9`   |
| `TASK-PLAT5-DEFECT-005` | §17.6 step 2 restricts the Job to "expand-phase only", but §18.1 stage 15 says "Migration Job then rolling update" without the qualifier, and a `CONTRACT` release must also run through a Job. Resolution: one declared classification per release, machine-checked     | `P2.4`, `P10.8`  |
| `TASK-PLAT5-OBS-001`    | The feature's observability expectation requires that a lock beyond threshold is "an alert, not a log line", but §16.2 defines no migration metric and §16.4 contains no migration alert row. Metric names and an alert are proposed and raised                        | `P9.5`, `P10.8`  |

---

# Phase 0 – Gate Prerequisites

`PLAN-BLOCKER-001` is discharged by `tasks.md` `P0.1`–`P0.7` and is **not** restated. Under a
`temporaryArchitectureGate` (`implementationAllowed: false`) only Phase 1 and Phase 2 tasks are authorised.

1. [*] Confirm which authorisation scope is in force from `tasks.md` `P0.5` or `P0.6` and record it. Deliverable: one-line phase-log entry. Acceptance: no Phase 3+ task starts under `implementationAllowed: false`.
2. [*] Confirm `FEAT-PLAT-002` has delivered the `app_migrator` role, the fifteen schemas, the `--migrate-only` entrypoint and the per-module Flyway locations this feature builds on. Deliverable: dependency-satisfied record. Depends on `tasks.md` `P3.4`, `P3.5`, `P3.12`.
3. [*] Obtain Platform Ops and Engineering Lead approval of the lock-duration thresholds (`TASK-PLAT5-DEFECT-001`) as the feature's additional Definition of Ready. Deliverable: signed threshold record naming the exam-critical fail value, the warn value and the non-critical value. Acceptance: no Phase 3 task runs against an unapproved threshold.
4. [*] Obtain approval of the forbidden-operation list as a **closed** list, with the agreement that adding a permitted DDL shape is a reviewed change to the allowlist rather than a configuration tweak. Deliverable: signed list.
5. [*] Confirm the production-shaped dataset generator is scheduled within this feature and that no production data extract will be used, with DPO acknowledgement. Deliverable: signed dataset-provenance statement.
6. [*] Confirm the universal Definition of Ready (plan §8.0) holds and record any item that does not, with its blocker. Deliverable: signed DoR record.

---

# Phase 1 – Discovery and Analysis

Satisfies the feature's additional Definition of Ready: thresholds and forbidden operations agreed, dataset
generator scheduled.

1. [*] Transcribe the §9.8 three-phase model — `EXPAND` (release N), backfill (release N), `MIGRATE` (N+1, writes both / reads new), `CONTRACT` (N+2) — with, for each, what may and may not appear in it. Deliverable: phase card. Acceptance: `CONTRACT` is recorded as a **separate release**, never combined.
2. [*] Transcribe the forbidden-operation list verbatim from §9.8 and `ADR-019`: renaming or dropping a column still read by the previous version; adding `NOT NULL` without a default; a blocking `ALTER TABLE` on `delivery.answer` / `delivery.attempt` / `audit.audit_event`; `CREATE INDEX` without `CONCURRENTLY`. Deliverable: forbidden-operation card. Acceptance: names are schema-qualified per `TASK-PLAT5-DEFECT-003`, and the exam-critical set is stated as extensible by the owning feature.
3. [*] Transcribe the §17.6 nine-step release sequence and mark which steps this feature owns (2, 9), which it gates (5, 6, 8) and which are `FEAT-OPS-007`'s. Deliverable: ownership-annotated sequence.
4. [*] Transcribe the §18.2 rollback table's six triggers with their bounds, and mark the two rows whose safety depends on this feature: manual `rollout undo` (<10 min, safe because *N−1* runs against *N*'s schema) and bad expand migration (forward fix, never rollback). Deliverable: rollback dependency card.
5. [*] Enumerate `ARC-OPS-006`, `ARC-OPS-008` and `ARC-OPS-013` with, for each, the verbatim rule, its enforcement mechanism and its owning task. Deliverable: rule card. Acceptance: each has a mechanism, not a convention — `-006` an N−1 test, `-008` a classification check, `-013` a fail-closed precondition.
6. [*] Map this feature's verification obligations to their CI stages: stage 12 (owned — lock duration, forbidden operations, N−1 compatibility), stage 19/20 (contributed — freeze precondition), §19.9 "Migration lock-duration report" (owned artifact). Deliverable: verification-ownership table. Acceptance: no new `ARC-VERIFY` identifier is created, and `TASK-PLAT5-DEFECT-002` is recorded.
7. [*] Derive the volumetric profile inputs from §15.2: 5,000 candidates per session and 50,000 platform-wide, ~60 questions per candidate, ~80 answer saves including revisions, ~274 requests per candidate, session-close burst of 5,000 submissions in ~120 s. Deliverable: volumetric input table — the generator's source of truth.
8. [*] Record the migration Job's connection contribution to the `ARC-PERF-006` envelope (`migration_job` term, §15.2) and to condition `A8`, which requires `ARC-VERIFY-033` to run with the migration Job active. Deliverable: envelope input record for `FEAT-OPS-004`/`FEAT-OPS-005`.
9. [*] Enumerate the `INVALID` index hazard: which operations can leave one, how it is detected, and why a retry must `DROP INDEX CONCURRENTLY` first. Deliverable: hazard card feeding `P2.7` and the `P9.6` runbook.

---

# Phase 2 – Architecture and Design

1. [*] Design the migration script header contract: a mandatory `-- cbt:phase`, `-- cbt:module`, `-- cbt:transactional` and `-- cbt:justification` directive set, parsed before any SQL is read. Deliverable: header specification. Acceptance: a script with a missing or unparsable header fails the gate; there is no default classification.
2. [*] Design the DDL **allowlist** grammar: the enumerated set of permitted statement shapes per phase, with everything unmatched rejected. Deliverable: allowlist specification. Acceptance: states explicitly that an unrecognised shape is a failure, not a pass, and names the review path for adding a shape.
3. [*] Design the static migration analyser as a standalone build-time Gradle module depending on a SQL parser and nothing from the application. Deliverable: analyser design note. Acceptance: the analyser has no compile dependency on any `com.cbt.platform` module, so it cannot be weakened by application refactoring.
4. [*] Design the release manifest: one declared migration classification per release, the previous release's image digest, and the migration set's checksum. Deliverable: manifest schema. Acceptance: resolves `TASK-PLAT5-DEFECT-005` — an `EXPAND`+`CONTRACT` mix in one manifest is a build failure.
5. [*] Design the lock-duration measurement harness: sample `pg_locks` joined to `pg_stat_activity` at a fixed interval for the migrator backend while each statement runs, and attribute the maximum hold per `(statement, relation, lock mode)`. Deliverable: measurement design note. Acceptance: measures **hold** duration per relation, not wall-clock statement time, so a long `CONCURRENTLY` build is not mistaken for a blocking lock.
6. [*] Design the `lock_timeout` preventive limb: the value set per migration session, and the distinct value used for `CONCURRENTLY` statements with their bounded retry. Deliverable: timeout design note. Acceptance: states why a single global value is wrong — a `CONCURRENTLY` build legitimately waits on `ShareUpdateExclusiveLock` and would fail spuriously.
7. [*] Design the `INVALID` index reconciliation: detection via `pg_index.indisvalid`, mandatory `DROP INDEX CONCURRENTLY` before retry, and the Flyway schema-history repair path after a failed non-transactional script. Deliverable: reconciliation design note. Acceptance: the path is idempotent and safe to run twice.
8. [*] Design the production-shaped dataset generator: declarative per-table volumetric rows, deterministic synthetic values from a fixed seed, referential integrity honoured, and index/statistics state realistic enough that the planner behaves as in production. Deliverable: generator design note. Acceptance: no personal data and no production extract; the same seed reproduces the same dataset byte-for-byte.
9. [*] Design the backfill harness: batched by primary-key range, throttled to a configured rate, resumable from a persisted cursor, idempotent per row, and executed in the `worker` role outside the migration Job. Deliverable: harness design note. Acceptance: resolves `TASK-PLAT5-DEFECT-004`; states that a backfill never blocks the release and never holds a table-level lock.
10. [*] Design the N−1-against-N compatibility test: start the previous release's retained image digest against the new release's migrated schema and exercise a fixed read/write conformance suite. Deliverable: compatibility-test design note. Acceptance: the previous digest is resolved from the release manifest, never rebuilt from source.
11. [*] Design the `SessionWindowQuery` port and its fail-closed semantics: `OPEN`, `NONE` or `UNKNOWN`, where `UNKNOWN` refuses. Deliverable: port specification. Acceptance: the port lives in the platform module; the adapter that answers from real session data is `FEAT-EXAM-001`'s to supply, and its absence yields `UNKNOWN`.
12. [*] Design the emergency-override path for `ARC-OPS-013`: an override requires a referenced incident record and both an Engineering Lead and Platform Ops approval, and is itself audited. Deliverable: override design note. Acceptance: the override cannot be set by an environment variable or a pipeline parameter alone.
13. [*] Design the `CONTRACT`-never-rolled-back enforcement (`ARC-OPS-008`): a rollback request against a release whose manifest declares `CONTRACT` is refused with the forward-fix instruction. Deliverable: enforcement design note.
14. [*] Design the §19.9 "Migration lock-duration report": per statement, the relation, lock mode, measured hold, threshold and verdict, plus the dataset seed and volumetric profile used. Deliverable: report schema. Acceptance: the report is reproducible — a reader can regenerate the dataset from the recorded seed.
15. [*] Design the migration telemetry and its alert per `TASK-PLAT5-OBS-001`: metric names, labels and the threshold-breach alert with its severity and first action. Deliverable: telemetry design note for `FEAT-OBS-001`/`FEAT-OPS-004` to register.
16. [*] Design the CI stage 12 entry point as a standalone, locally runnable task with the same behaviour in CI and on a developer machine. Deliverable: stage-12 contract, consistent with `tasks.md` `P3.7`.

---

# Phase 3 – Data and Infrastructure

1. [*] Create the standalone `migration-verify` Gradle module for the analyser, measurement harness and dataset generator, with no dependency on application modules. Deliverable: module plus build wiring. Depends on `P0.1`. Acceptance: a dependency on `com.cbt.platform` fails the build.
2. [*] Add the SQL parser dependency to lockfiles and pin it. Deliverable: dependency block plus lockfile update. Acceptance: CI stage 2 lockfile-drift gate stays green.
3. [*] Create `platform.migration_fixture` as a migration-verification fixture table with the columns needed to exercise every forbidden operation and every permitted shape. Deliverable: fixture migration. Acceptance: documented in-migration as a conformance artifact, not a business table, and carrying no personal data.
4. [*] Implement the volumetric profile as a committed declarative file with a row per table, seeded from `P1.7`, containing zero rows for tables that do not yet exist. Deliverable: `migration/volumetrics.yaml` plus its schema. Acceptance: an owning feature adds a table by adding one row; a table with no row is reported, not silently skipped.
5. [*] Implement the deterministic dataset generator against the profile. Deliverable: generator. Acceptance: two runs with the same seed produce identical row counts and identical checksums.
6. [*] Provision the CI stage 12 database as a Testcontainers PostgreSQL 17 instance on the pinned digest, sized for the generated dataset. Deliverable: stage-12 harness. Depends on `tasks.md` `P3.1`, `P3.3`.
7. [*] Configure the migration session parameters: `lock_timeout` at the approved fail threshold, `statement_timeout` sized for the longest permitted `CONCURRENTLY` build, and `idle_in_transaction_session_timeout`. Deliverable: session configuration. Depends on `P0.3`.
8. [*] Configure Flyway for non-transactional scripts so a `CONCURRENTLY` statement is not wrapped in a transaction, and disable out-of-order migration. Deliverable: Flyway configuration. Acceptance: a `CONCURRENTLY` script marked transactional fails the gate rather than failing at runtime.
9. [*] Configure the migration Job's own connection allowance and publish it as the `ARC-PERF-006` `migration_job` term. Deliverable: Job resource and pool configuration plus the envelope input record from `P1.8`.
10. [*] Add the release manifest to the build outputs, populated with the declared classification, the previous release's image digest and the migration checksum. Deliverable: manifest generation step.
11. [*] Add retention of the previous release's image digest in the registry and the manifest so the N−1 test always has a subject. Deliverable: digest retention policy. Acceptance: the N−1 test fails loudly if the previous digest is unavailable — it never skips.
12. [*] Add the CI stage 12 entry point per `P2.16`, wired after stage 11 and before stage 13, blocking, and runnable standalone. Deliverable: `ci/` script plus Gradle task, consistent with `tasks.md` `P3.7`, `P3.8`.
13. [*] Add `stage-12-migration` to the `main` branch-protection required checks. Deliverable: protection configuration record extending `tasks.md` `P3.9`.

---

# Phase 4 – Backend Implementation

1. [*] Implement the header parser from `P2.1`. Deliverable: parser. Acceptance: a missing, malformed or unknown-value header fails with a message naming the file and the expected directive set.
2. [*] Implement the DDL allowlist analyser from `P2.2` and `P2.3`. Deliverable: analyser. Acceptance: an unmatched statement shape is reported as a failure with its parsed form quoted, so the author can see what was rejected.
3. [*] Implement the forbidden-operation rules as individual named checks: column rename, column drop, `NOT NULL` without default, blocking `ALTER TABLE` on an exam-critical table, `CREATE INDEX` without `CONCURRENTLY`. Deliverable: five checks with per-rule failure messages. Acceptance: each rule reports independently, so one failure does not mask the rest.
4. [*] Implement the exam-critical table registry as a declarative list seeded with `delivery.answer`, `delivery.answer_operation`, `delivery.attempt` and `audit.audit_event`, extensible by the owning feature. Deliverable: registry plus its extension note.
5. [*] Implement the own-schema check for each script against its `-- cbt:module` directive, complementing `ARC-PLAT-009`'s grep with a parsed assertion. Deliverable: check. Acceptance: a script touching a foreign schema fails with both identifiers named.
6. [*] Implement the release-manifest classification check from `P2.4`: exactly one classification per release, and `EXPAND` and `CONTRACT` never in the same manifest. Deliverable: check.
7. [*] Implement the lock-duration measurement harness from `P2.5`. Deliverable: harness. Acceptance: attributes hold duration per relation and lock mode, and distinguishes `ShareUpdateExclusiveLock` from `AccessExclusiveLock`.
8. [*] Implement the threshold verdict: fail beyond the exam-critical value on an exam-critical relation, fail beyond the non-critical value elsewhere, warn at the warn value. Deliverable: verdict logic. Depends on `P0.3`.
9. [*] Implement the backfill harness from `P2.9`: key-range batching, configured throttle, persisted resume cursor, per-row idempotency. Deliverable: harness plus its `worker`-role entry point. Acceptance: interrupting and restarting it produces the same end state and no duplicate work.
10. [*] Implement the `INVALID` index reconciliation from `P2.7` as an idempotent operator command. Deliverable: command. Acceptance: safe to run when there is nothing to reconcile.
11. [*] Implement the N−1-against-N compatibility runner from `P2.10`. Deliverable: runner plus its conformance suite. Acceptance: the suite exercises reads and writes on every table the release's migration touched, not a fixed smoke set.
12. [*] Implement the `SessionWindowQuery` port and its `UNKNOWN`-returning default adapter. Deliverable: port plus default adapter. Acceptance: the default is registered only when no real adapter is present, and its presence is logged at startup.
13. [*] Implement the deploy-freeze precondition check consuming the port, refusing on `OPEN` and on `UNKNOWN`. Deliverable: check plus its non-zero exit contract for `FEAT-OPS-007`.
14. [*] Implement the emergency-override path from `P2.12`, requiring an incident reference and two named approvals, and emitting an audit record of the override. Deliverable: override implementation. Acceptance: an override without an incident reference is refused.
15. [*] Implement the `CONTRACT`-never-rolled-back refusal from `P2.13`. Deliverable: refusal plus its forward-fix message.
16. [*] Implement the §19.9 report emitter from `P2.14`, including the dataset seed and profile version. Deliverable: report emitter.
17. [*] Implement the migration metrics from `P2.15` — `migration_duration_seconds` by module and classification, `migration_lock_held_seconds` by relation, `migration_outcome_total` by classification and outcome, `migration_forbidden_operation_total`, `deploy_freeze_refusal_total` by reason. Deliverable: metric instrumentation.

---

# Phase 5 – Frontend Implementation

**Not applicable.** `FEAT-PLAT-005` is a build, migration and deployment-gate feature with no user interface.
The phase is retained so numbering stays comparable across sibling task files.

---

# Phase 6 – Security and Hardening

1. [*] Confirm the migration entrypoint is the only principal holding DDL privilege and that no application profile can assume `app_migrator`, extending `tasks.md` `P6.8`. Deliverable: DDL-privilege review (`ARC-PLAT-007`).
2. [*] Confirm the migration Job's workload identity is distinct from `api`, `worker` and `pindist`, and holds no grant beyond what migration requires. Deliverable: workload-identity review.
3. [*] Confirm the `app_migrator` credential resolves only from the secret manager and is never present in a pipeline log, a manifest or the release artifact. Deliverable: credential-handling review plus a clean secret-scan result.
4. [*] Confirm the generated dataset contains no personal data and no production extract, and that the generator has no network path to a production database. Deliverable: dataset-provenance attestation, referenced by `P0.5`.
5. [*] Confirm the migration Job cannot be triggered outside the pipeline, and that a manual invocation is audited with the invoking identity. Deliverable: invocation-control review.
6. [*] Confirm the emergency override cannot be exercised by a single individual and leaves an audit record naming both approvers and the incident. Deliverable: override control review.
7. [*] Confirm no migration script contains a data-modifying statement outside the declared backfill path, so a migration cannot silently alter accepted answers. Deliverable: DML-in-DDL check plus its review record. Acceptance: implemented as a check, not a review convention.
8. [*] Review the pipeline against the §22.2 `ARC-RISK-017` row and record how each mitigation limb is realised. Deliverable: risk-conformance record.

---

# Phase 7 – Testing and Quality Assurance

The distinguishing obligation of this feature: every gate must be **proven to fail on a real violation**. A
green stage 12 on a release with no migration proves nothing.

1. [*] Unit-test the header parser across a missing header, an unknown phase value, a malformed directive and a valid header. Deliverable: parser test set.
2. [*] Unit-test the allowlist analyser on each permitted shape and on a deliberately unrecognised shape. Deliverable: analyser test set. Acceptance: the unrecognised shape **fails**, proving fail-closed behaviour.
3. [*] Add a negative test per forbidden operation — column rename, column drop, `NOT NULL` without default, blocking `ALTER TABLE` on `delivery.answer`, `CREATE INDEX` without `CONCURRENTLY` — asserting each fails the build with its own message. Deliverable: five negative tests. Acceptance: this is the feature's DoD obligation that a deliberately forbidden operation fails the build.
4. [*] Add a negative test for a script whose `-- cbt:module` does not match the schema it touches. Deliverable: negative test.
5. [*] Add a negative test for a release manifest mixing `EXPAND` and `CONTRACT`. Deliverable: negative test (`TASK-PLAT5-DEFECT-005`).
6. [*] Integration-test the lock-duration harness against a deliberately blocking `ALTER TABLE` on the fixture table under concurrent DML, asserting the measured hold exceeds the threshold and the gate fails. Deliverable: integration test. Acceptance: the measurement is proven to detect a real lock, not merely to run.
7. [*] Integration-test that a permitted `CREATE INDEX CONCURRENTLY` on the fixture table under concurrent DML passes, proving the harness does not false-positive on `ShareUpdateExclusiveLock`. Deliverable: integration test.
8. [*] Integration-test the `lock_timeout` preventive limb: hold a conflicting lock and assert the migration fails fast within the timeout rather than queueing. Deliverable: integration test.
9. [*] Integration-test the `INVALID` index path: interrupt a `CONCURRENTLY` build, assert the index is detected as invalid, and assert the reconciliation command restores a retryable state. Deliverable: integration test.
10. [*] Test the dataset generator's determinism: the same seed yields identical checksums, and a profile change is reflected in the generated volumes. Deliverable: generator test set.
11. [*] Run the full migration set against the production-shaped dataset and assert every measured lock stays under threshold. Deliverable: CI stage 12 run plus the §19.9 report. Acceptance: this is the feature's primary acceptance outcome.
12. [*] Implement the N−1-against-N compatibility test and assert the previous release's digest reads and writes correctly against the new schema. Deliverable: compatibility test in CI stage 12 (`ARC-OPS-006`).
13. [*] Add a negative case to the N−1 test: introduce a breaking change — a dropped column the previous version reads — and assert the test fails. Deliverable: negative test with the change reverted and the failure retained as evidence.
14. [*] Test the deploy-freeze refusal: `OPEN` refuses, `UNKNOWN` refuses, `NONE` proceeds. Deliverable: three tests. Acceptance: this is the feature's DoD obligation that deploy-during-open-session refusal is demonstrated.
15. [*] Test the emergency override: refused without an incident reference, refused with one approval, permitted with two and an incident, and audited in every permitted case. Deliverable: override test set.
16. [*] Test the `CONTRACT` rollback refusal (`ARC-OPS-008`). Deliverable: test.
17. [*] Conduct the rollback rehearsal in staging: deploy release N+1 with an `EXPAND` migration, roll the code back to N, and assert N operates correctly with no data operation. Deliverable: retained rollback rehearsal record. Acceptance: rehearsed against real data volumes, not on an empty schema.
18. [ ] Conduct the backfill rehearsal: run a throttled backfill against the production-shaped dataset, interrupt it, resume it, and assert correctness, resumption and that answer-acceptance latency stays within `NFR-PERF-001` throughout. Deliverable: retained backfill rehearsal record.
19. [ ] Register the retained artifacts in the §19.9 evidence register: the migration lock-duration report, the rollback rehearsal record and the backfill rehearsal record. Deliverable: three register entries, with `TASK-PLAT5-DEFECT-002` noted against the missing verification identifier.
20. [ ] Run the full pipeline on a clean checkout and confirm stage 12 is blocking and green, and that a forced stage 12 failure prevents stages 13 onward from executing. Deliverable: pipeline run record referenced by the Phase 0 exit criteria.
21. [ ] Verify each acceptance outcome in the `FEAT-PLAT-005` feature card against a named task and its evidence. Deliverable: completed acceptance-outcome verification table.

---

# Phase 8 – Deployment and Release

1. [ ] Confirm the migration Job runs to completion before any pod serves traffic and that a failed Job aborts the release with no pod replaced, per §17.6 step 2. Deliverable: ordering evidence extending `tasks.md` `P8.1`.
2. [ ] Confirm a failed migration leaves the schema in a known state — either fully applied or fully unapplied for a transactional script, or reconcilable via `P4.10` for a non-transactional one — and never half-applied silently. Deliverable: failure-state evidence (`NFR-REL-004` limb).
3. [ ] Publish the release manifest as a release artifact so the change advisory and the rollback path can read the declared classification. Deliverable: manifest in the release bundle.
4. [ ] State the rollback path for this feature's own changes: gates and tooling are build-time, so a rollback is a code revert with no data operation. Deliverable: rollback statement.
5. [ ] Hand the freeze precondition, the manifest classification and the `CONTRACT` refusal to `FEAT-OPS-007` as the interfaces its canary and rollback automation consumes. Deliverable: interface handover record.
6. [ ] Report the `migration_job` connection figure into the `ARC-PERF-006` envelope and confirm condition `A8`'s requirement that `ARC-VERIFY-033` runs with the Job active is satisfiable. Deliverable: envelope and `A8` input record.
7. [ ] Record the deferrals with their owning features: canary, automatic rollback and the production approval gate (`FEAT-OPS-007`); the real `SessionWindowQuery` adapter (`FEAT-EXAM-001`); per-module migrations and backfills (each owning feature); migration dashboard panels (`FEAT-OPS-004`). Deliverable: deferral register.

---

# Phase 9 – Monitoring and Operations

1. [ ] Register `migration_duration_seconds` by module and classification, and confirm it records on every Job run. Deliverable: metric plus evidence.
2. [ ] Register `migration_lock_held_seconds` by relation and lock mode. Deliverable: metric plus evidence. Acceptance: emitted from the measurement harness, so the production figure and the CI figure share one definition.
3. [ ] Register `migration_outcome_total` by classification and outcome, and `migration_forbidden_operation_total`. Deliverable: two metrics plus evidence.
4. [ ] Register `deploy_freeze_refusal_total` by reason, distinguishing `SESSION_OPEN` from `SOURCE_UNKNOWN`. Deliverable: metric plus evidence. Acceptance: a sustained `SOURCE_UNKNOWN` rate means the freeze is fail-closed but blind, and is documented as such.
5. [ ] Raise `TASK-PLAT5-OBS-001` to `FEAT-OBS-001` and `FEAT-OPS-004`: propose a **P2** alert on `migration_lock_held_seconds` above threshold with "check for an open session and prepare a forward fix" as its first action, a **P1** on `migration_outcome_total{outcome="FAILED"}` in production, and a migration panel on the platform-health dashboard. Deliverable: gap record with the proposed alert and panel definitions.
6. [ ] Write the operations runbook for a failed migration: how to read the report, how to distinguish a lock failure from a forbidden operation, the `INVALID` index reconciliation, the Flyway schema-history repair path, and why a forward fix is preferred to a rollback. Deliverable: runbook.
7. [ ] Write the operations runbook for a blocked deploy: how to confirm an open session, how to find the session window, and the two-approval emergency-override procedure with its incident-record requirement. Deliverable: runbook.
8. [ ] Confirm migration duration, lock duration and outcome are recorded per release and that a threshold breach is alertable rather than only logged, as the feature's observability expectation requires. Deliverable: observability conformance record referencing `TASK-PLAT5-OBS-001` for the alert's registration owner.

---

# Phase 10 – Documentation and Knowledge Transfer

1. [ ] Publish the expand/contract phase model from `P1.1` as the normative migration discipline. Deliverable: `docs/migration-expand-contract.md`.
2. [ ] Publish the forbidden-operation list and the permitted-shape allowlist from `P1.2` and `P2.2`, each with the reason it exists. Deliverable: `docs/migration-forbidden-operations.md`. Acceptance: each entry states the failure it prevents, so the list is teachable rather than arbitrary.
3. [ ] Write the migration authoring guide for module owners: the header directives, own-schema only, `CONCURRENTLY` mandatory, what stage 12 will reject and how to run it locally. Deliverable: `docs/migration-authoring.md` — the guide every later feature follows, extending `tasks.md`'s `docs/module-migrations.md`.
4. [ ] Write the backfill authoring guide: batching, throttling, resumption, idempotency, and the rule that a backfill runs in the `worker` role and never in the migration Job. Deliverable: `docs/migration-backfill.md`.
5. [ ] Write the release-planning note explaining that schema work spans releases and must therefore be planned ahead of feature work, per `ADR-019`'s stated consequence and `T-11`. Deliverable: `docs/migration-release-planning.md`.
6. [ ] Publish the volumetric profile from `P3.4` with the instruction that an owning feature adds its row when it ships tables. Deliverable: documented profile plus its contribution procedure.
7. [ ] Write the threshold rationale note recording the approved values, how they were chosen and the review path for changing them. Deliverable: `docs/migration-lock-thresholds.md`. Depends on `P0.3`.
8. [ ] Raise `TASK-PLAT5-DEFECT-001` through `-005` and `TASK-PLAT5-OBS-001` to the Architecture Owner as documentation defects for the next baseline, each with the resolution this feature adopted. Deliverable: six defect records.
9. [ ] Update the plan §19 traceability matrix with this feature's evidence: task ranges, CI stage 12 gate status and retained artifacts. Deliverable: updated matrix rows.
10. [ ] Run a walkthrough with the engineering team covering the phase model, the header contract, why `CONCURRENTLY` is mandatory, the `INVALID` index hazard and how to plan a three-release schema change. Deliverable: session record plus attendance.

---

# Appendix A – Traceability

| Requirement / decision                                              | Architecture reference       | Tasks                                          | Verification                                        |
|---------------------------------------------------------------------|------------------------------|------------------------------------------------|-----------------------------------------------------|
| `NFR-MAINT-002` backward-compatible, zero-downtime-capable migration | §9.8, `ADR-019`              | `P1.1`, `P4.1`–`P4.6`, `P7.3`                  | CI stage 12 BLOCK                                   |
| `NFR-MAINT-001` backward-compatible API and event evolution (indirect) | §10.6, §11.3               | `P1.1`, `P10.5`                                | CI stage 9, owned elsewhere                         |
| `NFR-AVAIL-001` exam path ≥99.9%                                     | §17.6, §20.5                 | `P3.7`, `P4.8`, `P4.13`, `P7.6`, `P7.14`       | Lock bound plus deploy freeze                       |
| `NFR-REL-004` failed migration blocks rather than half-applies       | §17.6                        | `P4.10`, `P8.2`                                | Failure-state evidence                              |
| `CONSTRAINT-PLAT-001` Flyway on a separate JDBC datasource           | §9.8 `ARC-PLAT-007`          | `P0.2`, `P6.1`, `P8.1`                         | Ordering evidence; `FEAT-PLAT-002` owns the datasource |
| `ADR-019` expand/contract only                                       | §9.8, §21.0                  | `P1.1`, `P2.1`, `P2.4`, `P4.6`, `P7.5`         | CI 12 plus manifest classification check            |
| `ARC-PLAT-005` expand/contract as the only shape                     | §9.8                         | `P1.1`, `P4.2`, `P4.3`                         | Allowlist analyser                                  |
| `ARC-PLAT-009` per-module migration ownership                        | §9.8                         | `P4.5`, `P10.3`                                | Parsed own-schema check plus negative test `P7.4`   |
| `ARC-OPS-006` rollback is code-only, never schema                    | §17.6                        | `P1.4`, `P2.10`, `P4.11`, `P7.12`, `P7.17`     | N−1-against-N test plus rollback rehearsal          |
| `ARC-OPS-008` a contract migration is never rolled back              | §18.2                        | `P2.13`, `P4.15`, `P7.16`                      | Rollback refusal test                               |
| `ARC-OPS-013` deploys blocked while a session is open                | §20.5                        | `P2.11`, `P2.12`, `P4.12`–`P4.14`, `P7.14`     | Three-state refusal test, fail-closed               |
| `ARC-PERF-006` connection envelope, `migration_job` term             | §15.2                        | `P1.8`, `P3.9`, `P8.6`                         | CI stage 4a limb (b), owned by `FEAT-OPS-004/005`   |
| `ARC-RISK-017` migration lock stalls the exam path (High)            | §22.2                        | `P3.7`, `P4.7`, `P4.8`, `P6.8`, `P7.6`–`P7.8`  | Preventive timeout plus detective CI 12 gate        |
| `T-11` schema changes span releases                                  | §22.3                        | `P10.5`                                        | Release-planning note                               |
| §15.2 load profile as the dataset basis                              | §15.2                        | `P1.7`, `P3.4`, `P3.5`, `P7.10`                | Deterministic generator                             |
| §18.1 stage 12 gate                                                  | §18.1                        | `P3.12`, `P3.13`, `P7.11`, `P7.20`             | BLOCK on lock or forbidden operation                |
| §19.9 migration lock-duration report                                 | §19.9                        | `P2.14`, `P4.16`, `P7.19`                      | Retained artifact; `TASK-PLAT5-DEFECT-002`          |
| Condition `A8` — `ARC-VERIFY-033` with the migration Job active      | §24.3                        | `P1.8`, `P3.9`, `P8.6`                         | Contribution only; owned by `FEAT-OPS-005`          |
| Observability: a lock beyond threshold is an alert                   | §16.2, §16.4                 | `P2.15`, `P4.17`, `P9.1`–`P9.5`                | Metrics live; alert raised as `TASK-PLAT5-OBS-001`  |
| `PLAN-BLOCKER-001`                                                   | plan §10, §18.3              | `P0.1`                                         | Discharged by `tasks.md` `P0.1`–`P0.7`        |

---

# Appendix B – Exclusions

Everything below is deliberately **not** in this task list. Each is named so a reviewer can tell absence from
oversight.

| Excluded                                                                                                       | Owner                                    |
|----------------------------------------------------------------------------------------------------------------|------------------------------------------|
| Individual module migrations and their backfills                                                                | The owning feature per module            |
| Schemas, roles, the grant matrix, the RLS convention, the Flyway datasource and the `--migrate-only` entrypoint | `FEAT-PLAT-002`                          |
| Module boundaries, build, CI scaffolding and branch protection                                                  | `FEAT-PLAT-001`                          |
| Three runtime roles, scheduler singletons and graceful shutdown sequencing (`ARC-OPS-007`)                       | `FEAT-PLAT-006`                          |
| Deployment orchestration, SLO-gated canary, automatic rollback, the production approval gate                     | `FEAT-OPS-007`                           |
| The real `SessionWindowQuery` adapter answering from live session windows                                        | `FEAT-EXAM-001`                          |
| Metric registration, dashboard panels and alert-rule ownership                                                   | `FEAT-OBS-001`, `FEAT-OPS-004`           |
| The `ARC-PERF-006` envelope inequality and its CI stage 4a limb (b)                                              | `FEAT-OPS-004`, `FEAT-OPS-005`           |
| Backup, PITR and the data-corruption recovery path of §18.2                                                      | `FEAT-OPS-003`                           |
| API and event version compatibility gates (CI stage 9)                                                           | `FEAT-PLAT-004`, the owning contract feature |
| Partitioning, retention and disposition of `audit.audit_event`                                                   | `FEAT-PRIV-004`, `FEAT-AUD-001`          |
| Performance testing against the §15.2 profile (CI stage 17)                                                      | `FEAT-OPS-005`                           |
| Ratification of the architecture baseline as a governance act                                                    | `PLAN-BLOCKER-001`, Architecture Owner and Engineering Lead |

---

# Appendix C – Definition of Done

### Feature-specific (plan §8.1, verbatim obligations)

1. [ ] CI stage 12 is **BLOCKING** and green (`P3.12`, `P3.13`, `P7.20`).
2. [ ] A deliberately forbidden operation is demonstrated to fail the build — one negative test per rule (`P7.3`).
3. [ ] Deploy-during-open-session refusal is demonstrated, including the fail-closed `UNKNOWN` case (`P7.14`).
4. [ ] No migration performs a forbidden operation (`P4.3`, `P7.11`).
5. [ ] Every index is created concurrently (`P4.3`, `P7.7`).
6. [ ] Measured lock duration stays under threshold on production-shaped data (`P7.11`), with the §19.9 report retained (`P7.19`).
7. [ ] Version *N−1* runs correctly against version *N*'s schema, proven per release and proven to fail on a breaking change (`P7.12`, `P7.13`).
8. [ ] The rollback rehearsal is complete and retained (`P7.17`).
9. [ ] A contract-phase migration is a separate release and cannot be rolled back (`P4.6`, `P4.15`, `P7.5`, `P7.16`).
10. [ ] Migration duration, lock duration and outcome are recorded per release (`P9.1`–`P9.3`).

### Universal (plan §8.0), as far as this feature can discharge it

11. [ ] All mapped acceptance outcomes verified (`P7.21`).
12. [ ] Unit and integration tests pass; the analyser, harness and generator are covered including their negative cases.
13. [ ] CI stage 4 is green for the code this feature adds; the `migration-verify` module's isolation from application code is enforced (`P3.1`).
14. [ ] Required telemetry exists (`P9.1`–`P9.4`); the rollback path is stated (`P8.4`).
15. [ ] No credential, secret or token exists in source or in a pipeline log (`P6.3`).
16. [ ] Peer or AI review complete; no unresolved Critical or High defect remains.
17. [ ] The plan §19 traceability matrix is updated with the evidence (`P10.9`).
18. [ ] **Not dischargeable by this feature, and recorded as such:** tenant isolation and audit emission (no tenant-scoped slice and no business transaction is added here — the migration Job is a platform-scope operation, and its invocation audit is `P6.5`); the OpenAPI breaking-change diff (no API surface is added); the migration-threshold **alert** itself (`TASK-PLAT5-OBS-001`, `FEAT-OBS-001`/`FEAT-OPS-004`); the real session-window adapter that makes the freeze sighted rather than merely fail-closed (`FEAT-EXAM-001`); canary and automatic rollback (`FEAT-OPS-007`); the `ARC-PERF-006` envelope assertion (`FEAT-OPS-004`/`FEAT-OPS-005`).
