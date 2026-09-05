# Verification Ownership

Status: agreed discovery baseline for `FEAT-PLAT-001` (`P1.5`). Sources: architecture §§18.1 and 19.8.

| Verification | Normative pipeline location | Portion owned by `FEAT-PLAT-001` | Remaining owner or evidence |
|---|---|---|---|
| `ARC-VERIFY-001` | CI stage 4 | Closed Modulith boundaries, `api`-only imports, twelve-context assertion, ArchUnit backstop | Fully owned here; database ownership is separately covered by `ARC-VERIFY-002` |
| `ARC-VERIFY-003` | CI stage 4 | Slice-leaf rule, exactly-one transaction marker, no handler-to-handler call, framework-free domain | Fully owned here |
| `ARC-VERIFY-004` | CI stage 10 | Static inventory of tenant-scoped routes and the `TenantScopedQuery` signature contract used to build the matrix | The endpoint-by-endpoint cross-tenant execution matrix remains a security-suite obligation |
| `ARC-VERIFY-005` | Integration | Static R5 contract and schema ownership metadata establish which queries/tables require RLS | `FEAT-PLAT-002` supplies PostgreSQL RLS and proves omitted predicates return no foreign rows |
| `ARC-VERIFY-006` | CI stage 4 plus integration | Static outbox-only rule and compile-time `ADR-023` flow enumeration | `FEAT-PLAT-002` verifies grants/composite roles; `FEAT-PLAT-004` supplies the outbox implementation |
| `ARC-VERIFY-008` | Startup plus CI stage 10 | Startup fails unless every registered route resolves to exactly one `Policy` | CI stage 10 later performs the deny-by-default endpoint sweep |
| `ARC-VERIFY-016` | CI stage 13 | Documentation-conformance scanner and immutable enumerated allowlist | Fully owned here; retained output becomes documentation evidence |

The static work for `ARC-VERIFY-004` and `ARC-VERIFY-005` is preparatory and does not turn those runtime scenarios green. Architecture §19.8 does not name separate static identifiers for those portions, so this plan reuses the existing identifiers and records the split rather than inventing new `ARC-VERIFY` values.
