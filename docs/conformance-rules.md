# Conformance Rules

Status: published rule card for `FEAT-PLAT-001` (`P10.2`). Normative source:
architecture section 5.1. The executable R1-R8 checks run in the dedicated
`conformanceTest` source set as blocking CI stage 4.

| Rule | Required structure | Enforcement and verification | Owner | Failure message |
|---|---|---|---|---|
| R1 | A slice package is a leaf and nothing outside that slice imports it. | ArchUnit package-dependency check; `ARC-VERIFY-003`. | `FEAT-PLAT-001` | `R1 slice leaf violated: <origin> imports <target> from slice <slice>; depend on domain or a published api instead.` |
| R2 | A module imports only another module's declared, exported `api`; all internals remain private. | Closed Spring Modulith verification plus an ArchUnit backstop; `ARC-VERIFY-001`. | `FEAT-PLAT-001` | `R2 module boundary violated: <origin> imports non-api type <target> from <module>; only declared module::api dependencies are permitted.` |
| R3 | A `Queries` type references relations only in its owning module's schema. | Bytecode SQL-constant extraction and JSQLParser relation analysis; later backed by database grants; `ARC-VERIFY-002`. | `FEAT-PLAT-001` | `R3 schema ownership violated: <query> references <schema> but module <module> may reference only schema <module>.` |
| R4 | Each `Handler` owns exactly one reactive `REQUIRED` transaction and never invokes another handler. | ArchUnit annotation, return-type, propagation, and method-call checks; transaction integration evidence; `ARC-VERIFY-003`. | `FEAT-PLAT-001` | `R4 transaction boundary violated: <handler> <detail> and must not invoke another handler.` |
| R5 | Every method on a `TenantScopedQuery` accepts the tenant identifier. | ArchUnit signature check; later backed by forced RLS; `ARC-VERIFY-004` structural limb and `ARC-VERIFY-005` database limb. | `FEAT-PLAT-001` | `R5 tenant query violated: <query>.<method> accesses tenant-scoped data without a RequestTenantId parameter.` |
| R6 | Domain code has no Spring, R2DBC, Jackson, infrastructure, or slice dependency; production code uses the shared clock; grading uses exact decimal types. | ArchUnit dependency/method/signature checks; `ARC-VERIFY-003`. | `FEAT-PLAT-001` | `R6 domain purity violated: <class> <forbidden dependency, ambient time source, or floating-point detail>.` |
| R7 | Asynchronous cross-module state propagation uses the outbox; a synchronous write uses only a closed `ADR-023` atomic flow. | ArchUnit broker, `OutboxWriter`, command-port, and flow-enumeration checks; later database role audit; `ARC-VERIFY-006`. | `FEAT-PLAT-001` | `R7 propagation violated: <class> <detail>; use OutboxWriter or an ADR-023 enumerated atomic flow.` |
| R8 | A mutating handler emits at least one audit event inside its transaction. | ArchUnit transactional method-call check; later runtime audit coverage; `ARC-VERIFY-010`. | `FEAT-PLAT-001` | `R8 audit coverage violated: mutating handler <handler>.<method> can complete without emitting an audit event in its transaction.` |
| R9 | Every transaction installs one permitted role and tenant/platform context as its first statements, and every connection release resets context. | ArchUnit rejects direct connection/transaction access; `SecurityContextInitializer` guards statement order and release; login roles have no direct grants; adversarial pool-reuse tests prove every termination path; `ARC-VERIFY-024`. | `FEAT-PLAT-002` | `R9_UNDECORATED_CONNECTION_ACCESS` for a bypassing handler/query; `R9_CONTEXT_NOT_FIRST` for missing or incomplete context. Reset failure propagates and increments `db_connection_reset_failure_total`. |
| R10 | A handler assumes only its module role or an `ADR-023` enumerated composite role, with no later role switch. | ArchUnit enforces the closed handler-to-role/flow policy; `AssumableDatabaseRole` is an enum; `pg_roles` grant audit checks permitted memberships; the connection wrapper rejects later role statements; `ARC-VERIFY-006` and `ARC-VERIFY-023`. | `FEAT-PLAT-002` | `R10_DYNAMIC_ROLE_INPUT`, `R10_ROLE_NOT_ALLOWED`, or `R10_COMPOSITE_ROLE_UNDECLARED` for a static policy breach; `R10_ROLE_SWITCH` for repeated initialisation or a later role statement. |

## Ownership Boundary

`FEAT-PLAT-001` installs and proves the blocking static checks for R1-R8. Some
rules also require later runtime evidence: database grants and isolation belong
to `FEAT-PLAT-002`, the transactional outbox to `FEAT-PLAT-004`, and the audit
store to `FEAT-AUD-001`.

R9 and R10 are implemented by `FEAT-PLAT-002`. Their static policy checks run
in CI stage 4, their lifecycle checks run in unit and PostgreSQL integration
tests, and `ARC-VERIFY-024` is retained again from staging for launch condition
`L9`. The failure prefixes above are asserted by deliberate negative fixtures.

## Run the Rules

```bash
./gradlew conformanceTest
ci/stage-4
```

Each R1-R10 negative fixture asserts its stable prefix. A change that weakens or
removes an assertion therefore fails the negative test rather than silently
making the gate permissive. The detailed design and fixture strategy remain in
[`architecture/conformance-suite.md`](architecture/conformance-suite.md).
