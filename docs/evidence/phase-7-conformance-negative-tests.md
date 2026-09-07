# Phase 7 Conformance Negative-Test Evidence

- Feature: `FEAT-PLAT-001`
- Tasks: `P7.4`-`P7.11`
- Executed: 2026-09-06
- Command: `./gradlew conformanceTest --tests 'org.meldtech.platform.conformance.R*Tests'`
- Result: PASS; every isolated negative fixture caused its production conformance assertion to fail with the required stable diagnostic, and every production scan remained green.

| Rule | Deliberate fixture | Assertion retaining the failure | Required diagnostic observed |
|---|---|---|---|
| R1 | External import of a type under another slice | `R1SliceLeafTests.rejectsAnImportFromOutsideTheTargetSlice` | `R1 slice leaf violated:` |
| R2 | `academic` domain imports `tenancy` domain | `R2ModuleBoundaryTests.rejectsAnImportOfAnotherModulesDomain` | `R2 module boundary violated:` |
| R3 | `platform` query names `tenancy.institution` | `R3SchemaOwnershipTests.rejectsAQueryNamingAForeignSchema` | `R3 schema ownership violated:` |
| R4 | Handler invokes a handler in another slice | `R4TransactionBoundaryTests.rejectsAHandlerCallingAnotherHandler` | `must not invoke handler` |
| R4 | One handler declares a second transaction boundary | `R4TransactionBoundaryTests.rejectsASecondTransactionInOneHandler` | `must declare exactly one transactional entry method` |
| R5 | Tenant-scoped query has no tenant parameter | `R5TenantQuerySignatureTests.rejectsATenantQueryWithoutATenantIdentifier` | `R5 tenant query violated:` |
| R6 | Domain class imports Spring | `R6DomainPurityTests.rejectsASpringDependencyInDomainCode` | `R6 domain purity violated:` |
| R6 | Production class calls `Instant.now()` | `R6DomainPurityTests.rejectsAnAmbientTimeCallOutsideTheClockAbstraction` | `uses ambient time source` |
| R6 | Grading-domain record uses `double` | `R6DomainPurityTests.rejectsFloatingPointInTheGradingDomain` | `forbidden floating-point type` |
| R7 | Caller depends on a cross-module command without an enumerated atomic flow | `R7OutboxPropagationTests.rejectsAnUnenumeratedDirectCrossModuleWrite` | `R7 propagation violated:` |
| R8 | Mutating transactional handler emits no audit event | `R8AuditCoverageTests.rejectsAMutatingHandlerWithoutAnAuditEvent` | `R8 audit coverage violated:` |

Fixtures live only in the `conformanceTest` source set. Positive rules load `build/classes/java/main`, so the retained fixture classes cannot weaken or contaminate production verification. A negative test succeeds only after observing the expected rule failure; removing or weakening the production assertion makes that test fail.
