# Decision 0001: Context Module Count

- Status: Accepted for implementation; architecture documentation defect raised
- Date: 2026-09-04
- Owner for baseline correction: Architecture Owner
- Scope: `FEAT-PLAT-001` (`P1.3`)

## Decision

The application has exactly twelve context modules: `tenancy`, `iam`, `academic`, `people`, `questionbank`, `authoring`, `examaccess`, `delivery`, `grading`, `result`, `correction`, and `notification`. They map one-to-one to bounded contexts and to the first twelve schemas in architecture §9.2.

`shared`, `platform`, `audit`, and `outbox` form an enumerated platform-module category. `audit`, `outbox`, and `platform` own the additional schemas named in §9.2; `shared` owns no schema. These four modules are excluded from the twelve-context assertion, but they are closed Spring Modulith modules, export only explicitly named APIs, and remain fully subject to R2.

## Rationale

Architecture §6.2 draws `audit` among generic contexts, while §9.2 and the `FEAT-PLAT-002` feature definition describe `audit`, `outbox`, and `platform` as schemas in addition to the twelve. Treating every schema as one of the twelve would produce fifteen modules before `shared`, while treating `audit` as private infrastructure would defeat its ownership and boundary checks. The two-category model preserves the stated twelve-context acceptance criterion without exempting cross-cutting platform capabilities from conformance.

## Documentation Defect

The Architecture Owner is asked to update the next architecture baseline so §§6.2, 8.1, and 9.2 use the terms **context module** and **platform module** consistently, state that `shared` has no schema, and state that all sixteen modules are subject to R2. Until that correction is ratified, this decision is the implementation interpretation and conformance tests must assert both counts separately: twelve context modules and four enumerated platform modules.
