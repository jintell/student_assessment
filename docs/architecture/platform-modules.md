# Platform Module Specification

Status: normative design for `FEAT-PLAT-001` (`P2.3`). Decision basis: Decision 0001 and architecture §§8.4, 9.2, and 11.2.

| Module | Schema | Exported `api` responsibility | Allowed dependencies | Why it is outside the twelve-context count |
|---|---|---|---|---|
| `shared` | None | Typed identifiers, request/actor context contracts, clock, decimal conventions, problem contract, and transaction markers | None | A deliberately small technical kernel used across contexts; it owns no business aggregate or schema |
| `platform` | `platform` | Platform configuration, retention/legal-hold/DSR/breach contracts, plus the conformance reference endpoint | `shared::api`, `audit::api`, `outbox::api` | Cross-cutting governance/operations capability, not one of the twelve §6.2 business ownership boundaries |
| `audit` | `audit` | `AuditEmitter` and immutable audit-event input contracts | `shared::api` | Cross-cutting evidence capability consumed in every mutating transaction |
| `outbox` | `outbox` | `OutboxWriter`, integration-event envelope, and enumerated asynchronous propagation contracts | `shared::api` | Cross-cutting delivery mechanism, not a business bounded context |

All four are `ApplicationModule.Type.CLOSED`. Each root exports only packages annotated `@NamedInterface("api")`; `domain`, `slice`, `infra`, and `migration` remain private. The `shared` module is not configured as an implicit Spring Modulith shared module: consumers must name `shared::api` in `allowedDependencies`, which keeps R2 mechanically explicit.

The context descriptors from `P2.2` append these exact platform dependencies:

| Context module | Platform dependencies added |
|---|---|
| `tenancy` | `shared::api`, `audit::api`, `outbox::api` |
| `iam` | `shared::api`, `audit::api`, `outbox::api` |
| `academic` | `shared::api`, `audit::api` |
| `people` | `shared::api`, `audit::api`, `outbox::api` |
| `questionbank` | `shared::api`, `audit::api` |
| `authoring` | `shared::api`, `audit::api`, `outbox::api` |
| `examaccess` | `shared::api`, `audit::api`, `outbox::api` |
| `delivery` | `shared::api`, `audit::api`, `outbox::api` |
| `grading` | `shared::api`, `audit::api`, `outbox::api` |
| `result` | `shared::api`, `audit::api`, `outbox::api` |
| `correction` | `shared::api`, `audit::api`, `outbox::api` |
| `notification` | `shared::api`, `audit::api`, `outbox::api` |

`academic` and `questionbank` have no MVP integration event in architecture §6.9, so they are not pre-authorized to use `outbox::api`. Adding that dependency requires a concrete versioned event and a reviewed descriptor change. No context module is pre-authorized to import `platform::api`.

Conformance reports must present two separate assertions: exactly twelve context modules, and exactly four platform modules with the names above. Both categories are included in R2 import checks.
