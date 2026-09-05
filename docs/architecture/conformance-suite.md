# R1-R8 Conformance Suite Specification

Status: normative design for `FEAT-PLAT-001` (`P2.11`). The suite runs in the dedicated `conformanceTest` source set as blocking CI stage 4.

| Rule | Assertion and class-selection predicate | Stable failure message |
|---|---|---|
| R1 | For each package matching `org.meldtech.platform.<module>.slice.<slice>`, select all production classes outside that exact slice package tree and assert none depends on a class inside it. Test classes are excluded. | `R1 slice leaf violated: <origin> imports <target> from slice <module>.<slice>; depend on domain or a published api instead.` |
| R2 | Run `ApplicationModules.of(StudentAssessmentApplication.class).verify()`, assert twelve configured context ids plus four platform ids, then select cross-module dependencies and permit targets only in a declared `api` named interface listed by the consumer descriptor. | `R2 module boundary violated: <origin-module> imports non-api type <target> from <target-module>; only declared <module>::api dependencies are permitted.` |
| R3 | Select classes named `Queries`, types assignable to them, and module migration/query resources; scan SQL string constants with a PostgreSQL parser/tokenizer and reject qualified relations whose schema differs from the selected class's owning module. | `R3 schema ownership violated: <class> references <schema>.<relation> but module <module> may reference only schema <module>.` |
| R4 | Select classes named `Handler` under `..slice..`; require exactly one handler entry method with `@Transactional`, reject `REQUIRES_NEW`/nested transaction operators, and reject dependencies or method calls to any other `Handler`. | `R4 transaction boundary violated: <handler> must own exactly one transaction and must not invoke handler <target>.` |
| R5 | Select all types assignable to `TenantScopedQuery`; inspect every declared query method and require the tenant carrier as a parameter (the definitive `TenantId` after `FEAT-PLAT-003`). | `R5 tenant query violated: <type>.<method> accesses tenant-scoped data without a TenantId parameter.` |
| R6 | Select `..domain..` and reject dependencies on Spring, R2DBC, Jackson, `..infra..`, or `..slice..`; additionally select production code outside the shared clock and reject ambient time calls, and select grading/scoring types and fields/method signatures to reject `double`/`float`. | `R6 domain purity violated: <class> uses forbidden <dependency-or-primitive>; use domain types, the shared Clock, and exact Decimal conventions.` |
| R7 | Select all production classes except the outbox adapter/relay and reject direct broker-publish dependencies; select cross-module write ports and permit only `OutboxWriter` or a `(flow, role)` entry in the closed `ADR-023` enumeration. | `R7 propagation violated: <class> performs cross-module propagation through <target>; use OutboxWriter or an ADR-023 enumerated atomic flow.` |
| R8 | Select `Handler` classes classified as mutating a tenant-scoped aggregate; inspect the transactional method's bytecode call graph and require at least one `AuditEmitter.emit` call on every completing mutation branch. | `R8 audit coverage violated: mutating handler <handler> can complete without emitting an audit event in its transaction.` |

## Implementation Boundaries

- ArchUnit owns dependency, package, annotation, signature, primitive, and method-call assertions.
- Spring Modulith owns module discovery, cycle detection, named-interface visibility, and declared dependency verification.
- A structured PostgreSQL tokenizer/parser owns R3 relation extraction; regex-only SQL matching is not accepted.
- The R8 call-graph check is conservative: an unprovable branch fails and must be made structurally explicit, never allowlisted by name.
- Generated classes, tests, migrations produced by tooling, and framework internals are excluded by explicit package predicates, not broad ignore patterns.
- Failure messages above are stable prefixes. Reports append exact origin, target, module, slice, and source location where available.

## Reference and Negative Fixtures

The conformance reference slice must pass all eight assertions. Each negative test introduces exactly one isolated fixture violation and asserts the matching stable prefix; fixtures are outside production packages or generated in a temporary test workspace so the production tree is never left invalid.
