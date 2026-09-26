# FEAT-PLAT-002 Phase 7 Testing Evidence

## P7.11 R10 Negative Tests

Status: PASS (2026-09-26)

Two deliberate R10 violations prove that both enforcement limbs reject the
prohibited behavior:

| Violation | Test | Stable refusal |
|---|---|---|
| A handler accepts a free-form database role outside the compile-time enumeration | `R10DatabaseRoleAssumptionTests.rejectsAHandlerAcceptingADynamicRole` | `R10_DYNAMIC_ROLE_INPUT` |
| A transaction attempts `SET LOCAL ROLE app_people` after installing `app_delivery` | `SecurityContextInitializerTest.refusesARoleSwitchAfterInitialContextInstallation` | `R10_ROLE_SWITCH`; rollback, `RESET ROLE`, `RESET ALL`, and close all complete |

Verification:

```text
./gradlew conformanceTest \
  --tests 'org.meldtech.platform.conformance.R10DatabaseRoleAssumptionTests.rejectsAHandlerAcceptingADynamicRole' \
  test \
  --tests 'org.meldtech.platform.platform.infra.persistence.SecurityContextInitializerTest.refusesARoleSwitchAfterInitialContextInstallation'

BUILD SUCCESSFUL
```

## P7.12 ARC-VERIFY-024 Adversarial Pool Reuse

Status: PASS (2026-09-26)

`AdversarialConnectionReuseIntegrationTest` runs against migrated PostgreSQL 17
with a pool constrained to one physical connection. Nine transactions alternate
tenants A and B and distinct enumerated module roles across success, cancellation,
read-timeout, statement-timeout, and application-exception paths. Every snapshot
reports the expected tenant and role, and every snapshot has the same
`pg_backend_pid()`.

The suite also proves that:

- a raw pooled statement is refused with SQLSTATE `42501`;
- a recycled connection has no usable tenant value;
- strict `current_setting('app.tenant_id', false)` on a fresh uninitialised session
  raises SQLSTATE `42704`;
- `FORCE ROW LEVEL SECURITY` restricts the table owner to the selected tenant.

The suite exposed an eager callback invocation in `SecurityContextInitializer`.
Application work is now deferred until transaction start and context installation
complete, with a focused unit regression test preserving that ordering.

Verification:

```text
./gradlew integrationTest \
  --tests 'org.meldtech.platform.platform.infra.persistence.AdversarialConnectionReuseIntegrationTest' \
  --rerun-tasks

BUILD SUCCESSFUL

./gradlew ciStage8 --rerun-tasks

BUILD SUCCESSFUL
```
