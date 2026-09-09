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

## P0.3 - Blocked pending Security approval

`ci/architecture-ratification.json` records approval by the Architecture Owner
and Engineering Lead. No repository artifact records Security's agreement to
the architecture section 9.2 ownership table and grant matrix. Because `P0.3`
requires an approval from both the Architecture Owner and Security, it remains
open. No `P0.4` or Phase 1 task was started.
