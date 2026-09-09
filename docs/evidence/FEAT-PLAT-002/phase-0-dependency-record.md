# FEAT-PLAT-002 Phase 0 Dependency Record

Date: 2026-09-09

## P0.1 - Architecture authorization

The primary ratification path is in force. Baseline task `P0.5` is complete,
`ci/architecture-ratification.json` is `RATIFIED`, no
`temporaryArchitectureGate` is present, and `./ci/stage-4a` passes all twelve
checks. The authorization scope recorded in `ci/phase-0-entry-criteria.md`
allows production implementation and unblocks the listed Phase 1 and later
tasks, subject to feature-specific gates.

## P0.2 - FEAT-PLAT-001 dependency

Baseline tasks `P4.2` and `P4.5` are complete. The twelve context-module roots
and their Spring Modulith descriptors exist under
`src/main/java/org/meldtech/platform`, and
`src/main/java/org/meldtech/platform/shared/api/TenantScopedQuery.java`
provides the marker contract used by slice `Queries` ports.

Verification:

```text
./gradlew conformanceTest \
  --tests 'org.meldtech.platform.ContextModuleDependencyTests' \
  --tests 'org.meldtech.platform.ContextModuleStructureTests' \
  --tests 'org.meldtech.platform.conformance.R5TenantQuerySignatureTests'

BUILD SUCCESSFUL
```

## P0.3 — RESOLVED

Definition of Ready for architecture §9.2 Ownership Table and
Grant Matrix approved by Architecture Owner and Security.

Evidence:
- ci/dor/P0.3-dor-approval.json
- ci/dor/P0.3-dor-approval.architecture-owner.sig
- ci/dor/P0.3-dor-approval.security.sig

Architecture baseline:
arch-v1.4

CI gate:
verifyP03DefinitionOfReady

Result: PASS (2026-09-09)

P0.4 and Phase 1 work may proceed only when this verification passes.

## P0.5 - PostgreSQL version decision pending

The PostgreSQL 17 recommendation was raised in
`P0.5-postgresql-version-recommendation.md`. No Architecture Owner approval or
named alternative version is currently recorded, so `P0.5` remains open and
Phase 3 work remains blocked by this feature-specific prerequisite.
