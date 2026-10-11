# FEAT-PLAT-006 Phase 0 Gate-Prerequisite Record

Date assessed: 2026-10-10

Source plan: `../student_assessment/workspace/v3/be/plan/plan.md`, section 8.1
(`FEAT-PLAT-006`) and section 10. Task source:
`tasks/foundation/runtime/tasks.md`.

## P0.1 - Architecture Authorization

The primary `FEAT-PLAT-001 P0.5` ratification path is in force and unblocks
`FEAT-PLAT-006 P0.2-P0.8`, Phases 1-2 and Phase 3+ tasks subject to their own
dependencies and approvals; no `temporaryArchitectureGate` or
`implementationAllowed: false` restriction applies.

`tasks/foundation/baseline/tasks.md` marks `P0.5` complete. The current signed
`ci/architecture-ratification.json` is `RATIFIED` and pins architecture v1.4,
tag `arch-v1.4`, commit `8ea8250ef7ef6877eaf2d89444d473f7a4933fbc`, blob
SHA-256 `70497fdb08c93081f3f6d441a6c8e29dc0f758171e6d2bf30212fa6f55bb41da`.
The earlier commit and digest in `ci/phase-0-entry-criteria.md` are historical;
this assessment uses the current signed artifact and its verified tag/blob.

Verification on 2026-10-10:

```text
./ci/stage-4a
STAGE 4a: PASS
Step 12: architecture baseline arch-v1.4 is ratified and verified.
```

Status: SATISFIED. Feature-specific approvals remain separate prerequisites.

## P0.2 - Module Boundaries and Conformance Harness

Status: SATISFIED. `FEAT-PLAT-001 P4.1-P4.5` and `P4.24` are marked
complete in `tasks/foundation/baseline/tasks.md`. The twelve context modules
are `tenancy`, `iam`, `academic`, `people`, `questionbank`, `authoring`,
`examaccess`, `delivery`, `grading`, `result`, `correction`, and
`notification`. The four separately classified platform modules are
`shared`, `platform`, `audit`, and `outbox`.

`ContextModuleStructureTests` verifies the exact module sets and Modulith
boundaries; `ContextModuleDependencyTests` verifies the permitted dependency
matrix. The existing `conformanceTest` source set contains the R1-R8 rules
and negative fixtures; R9/R10 checks also passed in the rule run below.

The surrogate for runtime `P7.18` is
`org.meldtech.platform.platform.slice.getConformanceReference`, serving
`GET /api/v1/platform/conformance-reference`. Its endpoint, immutable
request/response, policy, transactional handler, queries port, infrastructure
adapter and `SliceTest` exist; `ReferenceSliceAnatomyTests` checks its anatomy.
The future surrogate drill must preserve its authorization policy. This
dependency check does not claim the replica-removal or mid-exam drill passed.

Numbering note: baseline `P4.24` is the R7 outbox-propagation rule; the reference
slice is baseline `P4.12-P4.17`, and the ratification gate is baseline `P4.26`.
The dependency was checked against actual task contents and artifacts.

Verification on 2026-10-10 (both commands passed):

```text
./gradlew compileJava compileTestJava conformanceTest \
  --tests 'org.meldtech.platform.ContextModuleStructureTests' \
  --tests 'org.meldtech.platform.ContextModuleDependencyTests' \
  --tests 'org.meldtech.platform.platform.slice.getConformanceReference.ReferenceSliceAnatomyTests' \
  --tests 'org.meldtech.platform.conformance.R?*Tests' --console=plain
./gradlew conformanceTest \
  --tests 'org.meldtech.platform.conformance.R*Tests' --console=plain
```

The first run selected the three explicitly named tests; Gradle did not
expand `?` as a wildcard, so the second run verified the rule suites using
its supported `*` filter.

## P0.3 - Persistence Dependency

Status: SATISFIED; carries `TASK-PLAT6-DEFECT-001`. The feature-card dependency
on `FEAT-PLAT-002` is fulfilled in this checkout. This verification does not
replace the plan-owner acknowledgement required by runtime `P0.5`.

Persistence `P1.6`, `P3.7`, `P3.11`, and `P4.14` are marked complete.
Numbering note: persistence `P4.14` now describes the RLS DDL template; the
implemented workload pool configuration is persistence `P3.14` (also complete).

Evidence:

- `src/main/resources/db/migration/platform/V1__create_schema.sql` creates
  `platform AUTHORIZATION app_migrator`.
- `src/main/resources/db/migration/platform/V3__create_pool_login_roles.sql`
  creates `app_api`, `app_worker`, and `app_pindist` as `LOGIN NOINHERIT`
  roles with explicit assumable-role membership. The live grant-matrix test
  validates the current grants after all migrations.
- `docs/evidence/FEAT-PLAT-002/phase-1-discovery.md`, `P1.6`, and
  `src/main/resources/application.yaml` supply the consumed pool figures.
- `WorkloadConnectionPoolConfiguration` and its tests implement separate
  API/general and exam-path pools, plus worker and PIN-distribution pools.

| Workload | Login role | Per-replica connection budget |
| --- | --- | --- |
| `cbt-api` | `app_api` | 14 total: separate pools of 6 general and 8 reserved exam-path connections |
| `cbt-worker` | `app_worker` | 10 total: 4 grading plus 6 shared background connections in the published allocation |
| `cbt-pindist` | `app_pindist` | 5 total |

The worker currently exposes a ten-connection pool; dedicated lock pools
and enforcement of the internal allocation belong to later runtime tasks.
This record consumes the existing budget and adds no connections.

Verification on 2026-10-10 with Docker and the approved PostgreSQL 17 image:

```text
./gradlew :test \
  --tests 'org.meldtech.platform.platform.infra.persistence.WorkloadConnectionPoolConfigurationTest' \
  integrationTest \
  --tests 'org.meldtech.platform.migration.PersistenceFoundationIntegrationTest.migrationsCreateOwnedSchemasAndIndependentHistories' \
  --tests 'org.meldtech.platform.platform.infra.persistence.PersistenceSecurityGatesIntegrationTest.liveDatabaseGrantsExactlyMatchTheDeclaredMatrix' \
  --console=plain
BUILD SUCCESSFUL
```

The ownership test checks all fifteen schemas and independent migration
histories; the pool tests cover all three workload profiles.

## P0.4 - Platform Ops Definition of Ready

Status: SATISFIED. Platform Ops approved the exact record at
`ci/dor/FEAT-PLAT-006/P0.4-runtime-role-dor.json`; the detached signature is
`P0.4-runtime-role-dor.platform-ops.sig`. The signer is Platform Ops approver
Josiah Adetayo, fingerprint
`7D509463FEB9C8CF34E081C35789DBF7FE24E1DB`, approved on 2026-10-10.

The signed record names architecture sections 8.2 and 17.1 and pins the baseline
read for both to architecture v1.4, tag `arch-v1.4`, commit
`8ea8250ef7ef6877eaf2d89444d473f7a4933fbc`, blob SHA-256
`70497fdb08c93081f3f6d441a6c8e29dc0f758171e6d2bf30212fa6f55bb41da`.
It records the three role/bean sets, one image/SBOM, the isolated `cbt-pindist`
ServiceAccount/workload identity, and the separation from the PIN-validation
path. It distinguishes the runtime identity mechanism from the later key grant,
business slice and real provisioning evidence.

`TASK-PLAT6-OBS-005`: the plan's universal DoR at line 448 refers to v1.3,
while Phase 0 entry at lines 3348-3353 refers to v1.4. Platform Ops explicitly
approved v1.4 as the version used because it is the current signed baseline and
the Phase 0 entry criterion names it; the universal DoR's v1.3 reference is
recorded as stale.

Verification on 2026-10-10:

```text
ci/verify-runtime-role-dor
RUNTIME ROLE DOR: PASS
Platform Ops approval, architecture v1.4, and sections 8.2 and 17.1 are verified.
```

The verifier checks the record against `ci/architecture-ratification.json`,
imports only the trusted public key into an isolated GPG home, and requires the
signature fingerprint to match the approved Platform Ops identity.

## Execution Summary Through P0.4

- Requested range: `0.1-0.9`. Phase 0 defines only `0.1-0.8`; no `0.9` task
  exists in the supplied task list. The missing endpoint was raised for
  clarification; no task was invented and Phase 1 was not entered.
- Completed and persisted individually: `P0.1`, `P0.2`, `P0.3`.
- Resumed and completed: `P0.4`, with the feature-specific signed Platform Ops
  DoR and its fail-closed verifier.
- Not started: `P0.5-P0.8`, outside this resumed task range.
- Changed artifacts: this phase log, the approved DoR JSON, its detached
  signature, the verifier, and the `P0.4` completion marker.
- Validation: Stage 4a passed; production/test compilation passed; module,
  reference-slice and conformance-rule checks passed; all three pool tests
  and both targeted PostgreSQL integration tests passed. Full test/build
  suites were not run for this documentation-only prerequisite assessment.
- Risks: later plan-owner and architecture/persistence-owner acknowledgements
  remain unverified because their tasks have not begun. Architecture
  ratification and this Platform Ops approval do not discharge those separate
  approvals.
- Next chunk: `P0.5-P0.8`; clarify or supply `P0.9` separately.

## P0.5 - Plan Dependency Correction

Date assessed: 2026-10-10

Status: SATISFIED.

The existing `P0.4` approval was verified again using
`./ci/verify-runtime-role-dor`; it passed. The retained persistence dependency
evidence from `P0.3` is available. This run starts at `P0.5` as requested.

`docs/defects/TASK-PLAT6-DEFECT-001.md` records the conflicting feature-card,
dependency-matrix and parallel-track statements and addresses the requested
correction to the Plan Owner. The supplied plan was amended directly at
`/Users/josleke/workspace/project/backend/student_assessment/workspace/v3/be/plan/plan.md`:

- Section 9.3 gives `FEAT-PLAT-006` a separate hard-dependency row naming both
  `FEAT-PLAT-001` and `FEAT-PLAT-002`.
- Section 11.2 makes the runtime track's persistence prerequisite explicit,
  covering workload login roles, the `platform` schema and pool budgets.

The Plan Owner accepted the corrected dependency row and track sequence in
`ci/dor/FEAT-PLAT-006/P0.5-plan-dependency-acknowledgement.json`. The signed
decision requires `FEAT-PLAT-002` before `FEAT-PLAT-006` and binds the exact
corrected plan bytes. The detached signature is
`P0.5-plan-dependency-acknowledgement.plan-owner.sig`.

Verification on 2026-10-10:

```text
ci/verify-runtime-plan-dependency-acknowledgement
RUNTIME PLAN DEPENDENCY ACKNOWLEDGEMENT: PASS
The Plan Owner accepted the corrected sections 9.3 and 11.2 dependency sequence.
```

No Phase 3 task began before this acknowledgement.

## Execution Summary Through P0.5

- Completed task: `P0.5`.
- Deliverables: the plan edits in the sibling `student_assessment` repository,
  `docs/defects/TASK-PLAT6-DEFECT-001.md`, and this execution record.
- Remaining tasks: `P0.6-P0.8` were not started, following the invoked
  execute-tasks skill's strict order and stop-on-blocking-dependency rule.
  `P0.9` is absent from the supplied task list; no substitute was invented.
- Progress: completed markers `P0.1-P0.5` are preserved; `P0.6-P0.8` remain
  open. Existing staged changes were preserved.
- Verification: `./ci/verify-runtime-role-dor` passed;
  `./ci/verify-documentation-conformance` passed;
  `./gradlew compileJava compileTestJava --console=plain` passed with all
  compile tasks up to date; `git diff --check` passed in both repositories.
  Tests were not rerun because this chunk changes documentation only.
- Risks: the later architecture/persistence-owner decisions and provisioning
  request remain outstanding. The plan and implementation records live in
  separate Git repositories, so both diffs must be retained together.
- Next chunk: `P0.6-P0.8`; clarify the missing `P0.9` before extending the range.

## P0.6 - Registry and Worker-Budget Decisions

Date assessed: 2026-10-11

Status: SATISFIED.

Before any `FEAT-PLAT-006` migration was authored, the Architecture Owner and
`FEAT-PLAT-002` owner adopted resolutions for both schema and budget defects:

- `TASK-PLAT6-DEFECT-002`: `platform.sweep_registry` is approved as an
  additive, non-tenant-scoped table owned by `app_migrator`, delivered by
  `FEAT-PLAT-006`, and required to join the platform-table catalogue.
- `TASK-PLAT6-DEFECT-003`: the existing worker pool remains 10 connections,
  split as 4 grading plus 6 background. One serialized sweep permit and one
  relay permit cap lock-holding connections at 2, leaving 4 background
  connections. This does not increase the published budget.

The two defect records are:

- `docs/defects/TASK-PLAT6-DEFECT-002.md`
- `docs/defects/TASK-PLAT6-DEFECT-003.md`

The adopted resolutions are bound in
`ci/dor/FEAT-PLAT-006/P0.6-registry-budget-approval.json`. Architecture Owner
Josiah Adetayo and `FEAT-PLAT-002` owner Francis Okechukwu signed the exact
record independently. A wider table scope, ownership change, third permit, or
pool increase requires renewed approval.

Verification on 2026-10-11:

```text
ci/verify-runtime-registry-budget-approval
RUNTIME REGISTRY AND BUDGET APPROVAL: PASS
The additive registry table and two-permit worker arithmetic are jointly approved.
```

The verifier checks the decision structure and arithmetic, pins the record to
the ratified architecture v1.4 baseline, imports only the two registered public
keys into isolated GPG homes, and verifies both detached signatures.

## Execution Summary Through P0.6

- Completed task: `P0.6`.
- Deliverables: two closed defect records, one jointly approved decision record,
  two detached owner signatures, the fail-closed verifier, and this phase log.
- Ordering: no runtime migration exists; both owners approved the adopted
  resolution before Phase 3 data work.
- Remaining tasks: `P0.7-P0.8` remain open and were not entered.

## P0.7 - ADR-012 Conditional Decision

Date assessed: 2026-10-11

Status: SATISFIED. Condition A8 remains OPEN.

`docs/evidence/FEAT-PLAT-006/P0.7-adr-012-conditional-decision.json`
records the ratified boundary:

- `ADR-012` is `Accepted - conditional` on `ARC-VERIFY-033` and may be
  implemented, but may not be cited as proven.
- `ARC-VERIFY-033` is condition A8, owned by `FEAT-OPS-005` in Phase 6.
- `FEAT-PLAT-006` produces the singleton limb: the lock mechanism and retained
  evidence that scheduler execution remains exactly-one.
- `FEAT-OPS-005` must run that limb at 24 worker replicas inside the complete
  simultaneous-ceiling CI 17 and staging scenario.

The handover is registered with evidence status `PENDING_IMPLEMENTATION`.
Neither that registration nor this Phase 0 task discharges `ARC-VERIFY-033` or
A8. The runtime task list names A8 as open, excludes its full verification from
this feature, and retains it in the not-dischargeable completion checklist.

Verification on 2026-10-11:

```text
ci/verify-runtime-conditional-decision
RUNTIME CONDITIONAL DECISION: PASS
ADR-012 remains conditional; the singleton limb is registered for FEAT-OPS-005 and A8 remains open.
```

The verifier checks the record against `ci/architecture-ratification.json` and
fails if the task list positively claims that A8 is discharged or that
`ARC-VERIFY-033` is complete.

## Execution Summary Through P0.7

- Completed task: `P0.7`.
- Deliverables: the conditional-decision and singleton-evidence handover record,
  its fail-closed verifier, and this phase log.
- Deferred owner: `FEAT-OPS-005`, Phase 6, for the complete
  `ARC-VERIFY-033` run and A8 closure.
- Remaining task: `P0.8` remains open and was not entered.

## P0.8 - Platform Ops Provisioning Request

Date requested: 2026-10-11

Status: REQUEST ACKNOWLEDGED; PROVISIONING NOT YET FULFILLED.

The signed request at
`ci/provisioning/FEAT-PLAT-006/P0.8-platform-ops-provisioning-request.json`
pulls the relevant plan section 11.1 stream into Phase 0 and carries
`PLAN-RISK-002`. Platform Ops owner Josiah Adetayo acknowledged ownership of:

- a production-shaped staging environment using synthetic data only;
- distinct, real provider-backed identities for `cbt-api`, `cbt-worker`, and
  `cbt-pindist`; and
- provider-backed key management capable of enforcing the later PIN-retrieval
  key policy.

`FEAT-PLAT-006` consumes the third role's identity mechanism. `FEAT-EXAM-002`
owns the retrieval-key grant, `ARC-VERIFY-025`, and condition A4. The request
does not prove that verification or discharge A4.

The request is intentionally non-blocking for this feature's implementation.
`P7.17`, which must inspect deployed ingress and Service values rather than a
template, remains blocked until provisioning is fulfilled. The same external
state is required before `FEAT-EXAM-002` can run the A4 denial attestation.

Verification on 2026-10-11:

```text
ci/verify-runtime-provisioning-request
RUNTIME PROVISIONING REQUEST: PASS
Platform Ops owns the Phase 0 request; implementation remains unblocked while P7.17 and A4 await real staging identities.
```

The verifier checks the request against the ratified architecture baseline,
requires the exact scope and ownership boundaries, verifies the Platform Ops
detached signature, and rejects any claim that the request itself is fulfilled
or discharges A4.

## Execution Summary Through P0.8

- Completed task: `P0.8`; all defined Phase 0 tasks are now complete.
- Deliverables: dated Platform Ops provisioning request, detached ownership
  signature, fail-closed verifier, explicit `P7.17` dependency, and this log.
- Non-blocking boundary: Phase 1 may proceed; only environment-dependent
  `P7.17` and `FEAT-EXAM-002` A4 evidence await provisioning.
- No environment, workload credential, KMS grant, or A4 evidence is claimed to
  exist in this repository.
