# Audit Security and Hardening

Execution date: 2026-10-08. Requested range: P6.1 through P6.12.

## P6.1 Privilege-refusal matrix

`AuditStoreHardeningIntegrationTest.everyApplicationRoleLacksMutationPrivileges`
discovers every `app_*` role except the schema owner `app_migrator` from the
live PostgreSQL catalog. Each role lacks both table privileges, and actual
UPDATE and DELETE attempts fail with SQLSTATE `42501`. Tenant context is set
to the seeded tenant. Migration uses the real module migrations and the approved
PostgreSQL 17 image, in an isolated test database.

| Roles | UPDATE | DELETE |
| --- | --- | --- |
| app_academic, app_authoring, app_correction, app_delivery | 42501 | 42501 |
| app_examaccess, app_grading, app_iam, app_notification | 42501 | 42501 |
| app_people, app_questionbank, app_result, app_tenancy | 42501 | 42501 |
| app_api, app_worker, app_pindist, app_readonly_ops | 42501 | 42501 |
| app_txn_examentry | 42501 | 42501 |
| app_audit_partition_maintenance, app_audit_retention, app_audit_sealer | 42501 | 42501 |
| app_outbox_maintenance, app_outbox_relay | 42501 | 42501 |

Validation: `./gradlew spotlessJavaApply compileJava compileTestJava integrationTest
--tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest'` passed;
44 attempts, zero failures or skips. The JUnit XML is generated under
`build/test-results/integrationTest/` and is not committed.

The owner is intentionally excluded from the application-role claim. These tests
do not assert that a database administrator cannot alter the schema or privileges.

## P6.2 Trigger backstop

`immutableTriggerRefusesMutationEvenWithPrivileges` temporarily grants SELECT,
UPDATE and DELETE to `app_delivery`, sets the seeded tenant, and confirms that
all 24 fixture records are visible. Both actual mutations fail with SQLSTATE
`23000` and `audit events are immutable`. Each transaction rolls back the
temporary grants; all 24 rows remain afterward. The trigger is the refusing
barrier in both cases. The focused Gradle integration-test run passed both cases.

## Previous execution stop (resolved)

- Completed: P6.1 and P6.2. Their task markers were persisted after each passing run.
- Failed validation: P6.3. The attempted Spring Data mapped-entity deletion test
  did not compile because `spring-data-r2dbc` and `spring-data-relational` are
  absent from the current dependency graph. The configured
  `spring-boot-starter-r2dbc` supplies lower-level R2DBC access, not entity mapping.
  The attempt also used an unavailable `SingleConnectionFactory(Connection,
  boolean)` constructor and needs the supported connection-factory API.
- The unbuildable P6.3 attempt was removed; no production dependency or lockfile
  was changed. P6.3 remains open, and P6.4 through P6.12 were not executed.
- Resume prerequisite: add a managed test-scoped Spring Data R2DBC dependency
  and refresh its dependency locks, then implement and verify the four deletion
  attempts using supported APIs. No separate user approval is inherently required
  for that reversible implementation work.
- Execution stopped under the execute-tasks skill's explicit build-failure rule.
  Recommended next range: P6.3 through P6.12.
- Remaining risk: this partial run does not establish the other hardening claims,
  including payload rejection, compliance-query isolation or KMS authorization.

The stop above records the original attempt. The P6.3 closure below supersedes
its blocker and resume point.

Modified artifacts: this evidence record, the Phase 6 markers in
`tasks/foundation/audit/tasks.md`, and
`src/integrationTest/java/org/meldtech/platform/audit/AuditStoreHardeningIntegrationTest.java`.

Final verification after removing the failed attempt:

```text
./gradlew compileJava compileTestJava spotlessCheck checkstyleIntegrationTest \
  integrationTest --tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest' \
  test --console=plain
```

BUILD SUCCESSFUL. All 46 audit-hardening integration cases passed, none skipped.
Compilation and unit-test tasks were up-to-date; formatting and integration
Checkstyle passed. `git diff --check` passed. The complete integration suite and
`clean build` were not run.

## P6.3 Deletion routes

Closure date: 2026-10-08. Resumed range: P6.3 only.

Added BOM-managed `spring-data-r2dbc` only to the integration-test configuration;
the lockfile now includes it and `spring-data-relational` at 4.1.1. Production
dependencies are unchanged. The mapped-entity test uses `ConnectionFactories`
and `R2dbcTransactionManager` with `TransactionalOperator`, so role, tenant
context, entity selection and deletion share one connection and transaction.
It does not use the unsupported `SingleConnectionFactory` constructor.

The existing PostgreSQL 17 fixture and real migrations exercise all four routes
as `app_delivery`. Before each attempt, all 24 seeded audit rows must be visible,
preventing an empty selection or RLS filter from masquerading as protection.

| Route | Adversarial operation | Verified refusal |
| --- | --- | --- |
| ORM / entity mapping | `R2dbcEntityTemplate` selects and deletes a mapped audit entity | `42501` without DELETE; `23000` with a temporary DELETE grant |
| Native query | Prepared SQL DELETE for the seeded tenant | `42501` without DELETE; `23000` with a temporary DELETE grant |
| Batch | JDBC prepared batch deleting two existing event IDs | `42501` without DELETE; `23000` with a temporary DELETE grant |
| Parent cascade | DELETE on a permitted fixture parent with an `ON DELETE CASCADE` foreign key from the real partitioned event table | `23000`, even though the caller has no audit DELETE privilege |

Every `23000` assertion also requires `audit events are immutable`, proving that
the intended trigger refused the operation. All 24 audit rows survive each
attempt; the cascade also preserves the parent. Temporary grants roll back.
The cascade constraint exists only inside the test transaction and rolls back;
no production migration or privilege is changed. The mapped entity is a minimal
test projection, not a new production persistence API.

Validation:

```text
./gradlew compileJava compileTestJava compileIntegrationTestJava --console=plain
./gradlew integrationTest --tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest' \
  spotlessCheck checkstyleIntegrationTest test --console=plain
```

BUILD SUCCESSFUL. JUnit reports 53 cases, zero failures, errors or skips: the
previous 46 checks plus seven cases across the four new routes. Production and
unit-test compilation and unit tests were up-to-date; integration compilation,
the focused PostgreSQL suite, formatting and Checkstyle passed. The complete
integration suite and `clean build` were not run. `git diff --check` passed.

P6.3 is complete. P6.4 through P6.12 remain open; resume at P6.4. This closure
establishes deletion resistance only and does not discharge the remaining
payload, isolation, signing, threat-model or secret-scan tasks.

## P6.4 Anchor privilege review

Resumed range: P6.4 through P6.12, 2026-10-08.

`anchorPrivilegesMatchApprovedMinimum` compares effective PostgreSQL privileges
for all 22 catalog-discovered application/workload/composite roles, all three
anchor relations, and SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES and
TRIGGER. All 462 assertions passed against the real migrations.

| Principal | Root head | Checkpoint | Seal |
| --- | --- | --- | --- |
| Ordinary application, composite, maintenance and retention roles | None | None | None |
| Dedicated `app_audit_sealer` | SELECT, UPDATE | INSERT | SELECT, INSERT |

This is the minimum approved in P2.5. The sealer's root-head CAS UPDATE is an
intentional exception to the ordinary application-role prohibition. No runtime
role has UPDATE on a checkpoint or seal. The migration owner and cluster owner
are administration identities, excluded from that runtime claim; owner access
means the literal phrase "no role" cannot include administrators. No grant or
production code was changed.

Validation: production/test compilation and the focused integration test passed
via `./gradlew spotlessJavaApply compileJava compileTestJava integrationTest
--tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest.anchorPrivilegesMatchApprovedMinimum'`.

## P6.5 Original failure (resolved below)

`R2dbcAuditEmitterTest.productionProfileRejectsSecretPayloads` executes all twelve
cases with the real emitter, payload policy, retention resolver and canonical
codec in a Spring context whose active profile is `production`. The append store
is a test double and the caller transaction is supplied through Reactor context.
This is a focused production-profile emitter test, not a deployed application or
a database persistence test. All values are synthetic.

| Secret | Top-level field | Nested field | Exception-message string |
| --- | --- | --- | --- |
| PIN | Rejected | Rejected | ACCEPTED: failure |
| OTP | Rejected | Rejected | ACCEPTED: failure |
| Token | Rejected | Rejected | ACCEPTED: failure |
| Password | Rejected | Rejected | ACCEPTED: failure |

The exception fixture serializes `IllegalStateException.getMessage()` under
`exception_message`, containing a named secret assignment. In each failing case,
the emitter completed successfully instead of raising `SecretAuditFieldException`.
`AuditPayloadPolicy.inspect` checks object member names and recurses into objects
and arrays, but never inspects string contents. The field-name-only guard thus
allows these secret-bearing messages to reach the append path. This is a security
gap against REQ-SEC-004 and REQ-RSLT-021, not an environment or credential issue.

Reproduce:

```text
./gradlew spotlessJavaApply compileJava compileTestJava test \
  --tests 'org.meldtech.platform.audit.infra.R2dbcAuditEmitterTest.productionProfileRejectsSecretPayloads' \
  --console=plain
```

Compilation passed. JUnit: 12 cases, 8 passed, 4 failed, zero skipped. Failure:
`expected: onError(SecretAuditFieldException); actual: onComplete()`.
An initial test-fixture NullAway compile error on nullable exception messages was
corrected with `Objects.requireNonNull` before the adversarial run. The retained
test compiled and was retained red at that stop pending the production guard fix.

## Previous P6.4-P6.12 Execution Summary

- Completed: P6.4, with its marker persisted immediately after verification.
- Failed: P6.5; the production guard accepts secret-bearing exception messages.
- Not executed: P6.6 through P6.12, under the execute-tasks failure-stop rule.
- Deliverables: anchor privilege matrix and executable catalog checks; twelve
  adversarial payload tests; this failure record and updated task markers.
- Resume prerequisite: fix the audit payload guard's handling of exception-message
  strings, preserving permitted non-secret fields and the approved `policy_key`
  exception, and make all twelve regression cases pass. Review other free-text
  payload paths against the same requirement before claiming the gap closed.
- Risk: secrets can enter immutable audit evidence through string payloads; this
  run does not establish tenant-query, signing, threat-model or secret-scan claims.
- Recommended next range: P6.5 through P6.12 after addressing the guard defect.

Existing P6.1-P6.3 changes, build dependencies, lockfile changes and signature
artifacts were preserved. No production code or database grants changed in this
execution. The full test suite and `clean build` were not run after the targeted
security failure.

Final artifact checks: `compileTestJava`, `spotlessCheck`, `checkstyleTest`,
`checkstyleIntegrationTest` and `git diff --check` passed. These checks do not
override the four failing P6.5 security assertions.

## P6.5 Closure

Closed 2026-10-09. This record supersedes the P6.5 failure and resume prerequisite
above. The defect was in production validation: `AuditPayloadPolicy` traversed
member names, objects and arrays but ignored `StringValue` contents.

The guard now scans every string leaf for named assignments (`name=value` and
`name: value`, including quoted names) and checks each name with the existing
`SecretFieldPattern`. This reuses its case/camel/segment handling and the approved
`policy_key` exception. Exception messages, free-text reasons and strings within
arrays receive the same check. Rejection reports only the containing field path,
never the secret value or original exception.

Exact `RETENTION_CLASS:YYYY-MM-DD` references remain permitted: hold events use
that format and `PIN_SECURITY_EVENT` is a valid retention class. This exception
matches the entire value; a secret appended to it is rejected. Ordinary text
such as `PIN validation failed` and non-secret `policy_key` assignments also pass.

| Secret | Top-level field | Nested field | Exception-message string |
| --- | --- | --- | --- |
| PIN | Rejected | Rejected | Rejected |
| OTP | Rejected | Rejected | Rejected |
| Token | Rejected | Rejected | Rejected |
| Password | Rejected | Rejected | Rejected |

All twelve production-profile emitter cases assert `SecretAuditFieldException`,
no secret in the diagnostic, and zero interactions with the append store,
including its chain-head lock. The real policy still runs before retention
resolution, canonicalization and persistence. The focused Spring context uses
the production profile; this evidence does not claim a deployed environment test.

Additional policy regressions cover quoted JSON-like assignments, URLs, camel
case, whitespace, nested arrays/objects, safe text and the allowlist. Review of
the five production event builders found the free-text outbox redrive `reason`,
disposition/hold references, and compliance/provisioning metadata all pass through
the same emitter guard. No production event builder in those paths copies
`Throwable.getMessage()` into a payload. This guard recognizes named secret
assignments; it does not claim to identify arbitrary unlabelled or encoded secret
bytes. Callers must continue to supply only approved non-secret payload data.

Validation:

```text
./gradlew spotlessJavaApply compileJava compileTestJava test \
  spotlessCheck checkstyleMain checkstyleTest --console=plain
```

BUILD SUCCESSFUL. The full unit-test task passed, including all twelve
production-profile cases, both existing emitter tests and all policy regressions.
Compilation, formatting, main/test Checkstyle and `git diff --check` passed.
The integration suite and `clean build` were not rerun for this domain-validation
change. Existing uncommitted work was preserved.

P6.5 is complete. Resume at P6.6; P6.6 through P6.12 remain open.

## P6.6 Absence-of-override review

Reviewed 2026-10-09 for the requested P6.6-P6.12 range.

The sole production `AuditEmitter` implementation, `R2dbcAuditEmitter`, calls
`AuditPayloadPolicy.requireSecretFree` unconditionally as the first operation in
its deferred emission. There is no catch-and-continue branch. Its constructor
accepts retention resolution, shard counts, IDs, the canonical codec and storage;
none can replace or disable the static guard. Validation runs before any of them
can reach storage.

`AuditPayloadPolicy` and `SecretFieldPattern` are final classes with private
constructors and fixed validation rules. Neither reads Spring profiles,
environment variables, system properties, configuration or feature flags. The
approved `policy_key` and exact epoch-reference exceptions are code-defined
rules, not runtime switches.

Review covered the audit production package, shared secret-field rules,
`src/main/resources`, `config/audit`, and audit KMS/deployment templates. No
profile annotation, conditional bean, injected property, environment/property
lookup or payload-disable flag was found on this path. CI configuration marks
the payload secret scan blocking; it does not configure runtime validation.
This review describes the current source and does not claim that arbitrary code
replacement or unlabelled/encoded secrets are prevented.

Validation: `./gradlew compileJava compileTestJava test
--tests 'org.meldtech.platform.audit.infra.R2dbcAuditEmitterTest'
--tests 'org.meldtech.platform.audit.domain.AuditPayloadPolicyTest'` passed.
All 40 focused cases passed, including the twelve production-profile adversarial
cases. No production code change was needed.

## P6.7 Original dependency review

Reviewed 2026-10-09. P4.29 is marked complete, but its runtime prerequisites for
this verification are missing from the current repository:

- `audit/slice/getComplianceAuditEvents/Queries.java` declares `find` but has no
  production implementation. Searches of production sources for implementations,
  interface references and the slice package found no SQL query adapter.
- `Endpoint`, `Handler` and `Policy` exist as constructor-injected classes, but
  have no component annotations or bean registration. There is no production
  reference that constructs the compliance endpoint or policy.
- `AuditComplianceCapabilityView` and `AuditQueryCatalogue` have no production
  implementations or wiring. Existing `ComplianceQueryContractTest` and
  `ComplianceReadAuditingTest` construct these dependencies with test lambdas.
- The isolation gate discovers actual `PolicyProtectedRoute` beans; its current
  scenario provider covers only `platform.getConformanceReference`. Adding an
  audit matrix row alone would not demonstrate an operational route.

P2.17 requires actor-derived tenant scope, real query execution under the database
tenant context, and a non-disclosing 404 for cross-tenant identifiers. A test that
substitutes a new query fake or test-only route could exercise individual classes,
but would not confirm these guarantees for the application's compliance query.
Existing RLS migrations alone cannot establish that missing runtime integration.

No cross-tenant HTTP/RLS attempt was claimed, no isolation-matrix entry was
fabricated, and P6.7 remains open. The execute-tasks dependency rule requires a
stop here. The earlier P4.29 marker was left unchanged because it is outside the
requested range; this record identifies the discrepancy for repair.

## P6.6-P6.12 Execution Summary

- Completed: P6.6, absence-of-override review, marker persisted after verification.
- Blocked before implementation: P6.7, missing production compliance-query adapter,
  authoritative capability/catalogue dependencies and Spring route/policy wiring.
- Not executed: P6.8 through P6.12; strict ordering stopped at P6.7.
- Deliverables: this review and dependency record; updated P6.6/P6.7 task entries.
- Verification: production/test compilation succeeded and all 40 focused payload
  policy/emitter cases passed. Full integration tests and `clean build` were not
  run for this documentation/review change.
- Risk: a completion marker and unit tests over constructor-injected fakes do not
  prove that the compliance endpoint is deployed or tenant-isolated. Signing,
  nullability, threat-model and secret-scan claims remain unverified in this run.
- Resume prerequisite: complete the P4.29 production adapter and wiring, including
  the authoritative tenant capability decision and database security context,
  then execute P6.7 against that path with the RLS and isolation-matrix assertions.
- Recommended next range after prerequisite repair: P6.7 through P6.12.

All existing implementation, test, dependency, lockfile and signature changes
were preserved. This execution changed only this evidence document and the
requested task-list entries.

## P6.7 Prerequisite repair

Reviewed and implemented 2026-10-09. Status: PARTIAL; P6.7 is not closed.
This record supersedes the earlier claim that no production query adapter exists.

`audit.infra.R2dbcComplianceAuditQueries` now implements `Queries` using the
caller-owned `TransactionalConnection`. It binds tenant/filter/cursor/limit
values, projects only the compliance DTO columns, supports all three query modes,
and applies inclusive-from/exclusive-to occurrence bounds and the captured `asOf`.
Keyset comparison follows the specified mixed sort directions. Limit and cursor
tenant/filter/snapshot mismatches fail before database access. It cannot acquire
a separate connection or independently commit before the read-audit append.

Real PostgreSQL 17 verification in `AuditStoreHardeningIntegrationTest`:

| Test | Result |
| --- | --- |
| `complianceAdapterFiltersAndPaginatesRealRows` | 24 fixture rows mapped; first page and continuation reproduce the ordered result without overlap; entity history returns its three rows; event-type timeline returns 24; SQL-shaped entity input returns zero. |
| `complianceAdapterAppliesInclusiveFromAndExclusiveTo` | Exactly four rows at the inclusive lower timestamp; upper-bound rows excluded. |
| `auditRlsHidesForeignRowsEvenWithoutTenantPredicate` | Predicate-free SELECT sees 24 rows in their owning tenant and zero in a different tenant; passing the victim tenant explicitly to the adapter still returns zero under the foreign database scope. |

The RLS test assumes `app_delivery` and temporarily grants SELECT in a transaction
that is always rolled back. This isolates the installed RLS policy from missing
reader privileges. It is **not** the requested production compliance role or HTTP
authorization path, and cannot discharge P6.7 or justify an endpoint matrix row.

Runtime review confirmed additional prerequisites, now recorded with owners and
a concrete proposed grant amendment in `docs/defects/TASK-AUD1-BLOCKER-002.md`:

- FEAT-IAM-003's authoritative tenant capability implementation and authenticated
  actor binding do not exist. IAM/tenancy are package and schema scaffolding;
  token roles cannot replace platform authority under the approved plan.
- The signed P0.4/P2.16 decisions do not authorize a compliance SELECT grant or
  its pool-role membership. Their change policies require Architecture Owner and
  Security approval plus FEAT-PLAT-002 owner acknowledgement.
- A secured audit transaction boundary and the complete endpoint/policy/catalogue/
  cursor/emitter/provider bean graph are still required. `@Transactional` alone
  does not install a tenant database context or the emitter's connection handle.

P4.29 was reopened because its runtime deliverable is incomplete. P6.7 stays open,
as do P6.8 through P6.12. No approval signatures, runtime grants, authoritative
capability decisions or HTTP isolation results were manufactured.

Validation: the full unit-test task passed. Final focused verification after
wrapping three Checkstyle line-length violations:

```text
./gradlew spotlessJavaApply compileJava compileTestJava test \
  --tests 'org.meldtech.platform.audit.infra.R2dbcComplianceAuditQueriesTest' \
  spotlessCheck checkstyleMain checkstyleTest checkstyleIntegrationTest \
  integrationTest --tests 'org.meldtech.platform.audit.AuditStoreHardeningIntegrationTest' \
  --console=plain
```

BUILD SUCCESSFUL. Seven adapter unit cases and 518 hardening integration cases
passed, with zero failures, errors or skips. Compilation, formatting, all three
Checkstyle checks and `git diff --check` passed. The full integration suite and
`clean build` were not run. Existing uncommitted work was preserved.
