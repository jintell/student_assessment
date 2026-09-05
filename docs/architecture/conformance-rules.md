# Conformance Rule Card

Status: agreed discovery baseline for `FEAT-PLAT-001` (`P1.4`). Normative source: architecture §5.1; verification catalogue: §19.8.

| Rule | Normative statement | Enforcement mechanism | Owning feature | Existing verification |
|---|---|---|---|---|
| R1 | A `slice` package is a leaf and is imported by nothing. | ArchUnit package-dependency rule | `FEAT-PLAT-001` | `ARC-VERIFY-003` |
| R2 | A module imports only another module's exported `api`; `domain`, `slice`, `infra`, and `migration` remain private. | Closed Spring Modulith modules plus an ArchUnit backstop | `FEAT-PLAT-001` | `ARC-VERIFY-001` |
| R3 | A `Queries` type references tables only in its owning module's schema. | ArchUnit SQL-literal scan; later backed by per-module grants | `FEAT-PLAT-001` | `ARC-VERIFY-002` |
| R4 | Each `Handler` invocation has exactly one transaction and no handler calls another handler. | ArchUnit annotation and dependency rules; transaction integration evidence | `FEAT-PLAT-001` | `ARC-VERIFY-003` |
| R5 | Every tenant-scoped `Queries` method accepts `TenantId`. | `TenantScopedQuery` signature rule; later backed by forced RLS | `FEAT-PLAT-001` | `ARC-VERIFY-004` structural limb; `ARC-VERIFY-005` database limb |
| R6 | `domain` imports no Spring, R2DBC, Jackson, or infrastructure type. | ArchUnit forbidden-dependency rule; companion clock and exact-decimal rules run in CI 4 | `FEAT-PLAT-001` | `ARC-VERIFY-003` |
| R7 | Asynchronous cross-module state propagation uses the outbox; only an `ADR-023` enumerated synchronous atomic collaboration may write across module schemas. | ArchUnit dependency/annotation rules plus later schema grants and flow enumeration audit | `FEAT-PLAT-001` | `ARC-VERIFY-006` |
| R8 | A handler mutating a tenant-scoped aggregate emits at least one audit event in the same transaction. | ArchUnit mutation/emission convention plus integration audit coverage | `FEAT-PLAT-001` | `ARC-VERIFY-010` |
| R9 | The first transaction statements install one permitted role and tenant/platform context; pooled connections are reset on every release path. | Connection-factory decorator, privilege-denied default, and adversarial pool-reuse integration test | `FEAT-PLAT-002` | `ARC-VERIFY-024` |
| R10 | A handler assumes only its module role or an `ADR-023` enumerated composite role, with no later role switch. | ArchUnit flow-to-role enumeration plus `pg_roles` grant audit and fault-injection test | `FEAT-PLAT-002` | `ARC-VERIFY-006`, `ARC-VERIFY-023` |

R1-R8 are installed and made blocking by `FEAT-PLAT-001`. Where a rule also needs database or audit infrastructure, this feature owns the blocking static contract while `FEAT-PLAT-002`, `FEAT-PLAT-004`, or `FEAT-AUD-001` supplies the runtime mechanism. R9-R10 are enumerated here but owned and implemented by `FEAT-PLAT-002`.

No new `ARC-VERIFY` identifier is introduced by this rule card.
