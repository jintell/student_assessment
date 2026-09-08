# Slice Authoring Guide

Status: normative guidance for slices delivered after `FEAT-PLAT-001`. The
architecture contract is defined by architecture section 5.1 and the detailed
design in [`architecture/vertical-slice-template.md`](architecture/vertical-slice-template.md).

## Add a Slice

1. Choose the owning context module. A slice belongs to exactly one module and
   must not read another module's schema.
2. Name the use case with a lower-camel-case verb and noun, for example
   `getConformanceReference`, and create
   `org.meldtech.platform.<module>.slice.<verbNoun>`.
3. Add the six mandatory production files below. Keep them in the slice package;
   infrastructure adapters may live in a child `infra` package and implement the
   slice-owned port.
4. Add `SliceTest.java` at the matching path under `src/test/java`. Exercise the
   policy and handler with in-memory ports without starting Spring.
5. Add endpoint and adapter integration tests when framework binding,
   persistence, transactions, or security wiring is part of the behavior.
6. Run the local gates before opening a change for review.

## Mandatory Anatomy

| File | Responsibility |
|---|---|
| `Endpoint.java` | Bind one route, decode and validate input, obtain request context, apply the policy, and delegate to the handler. |
| `Request.java` | Define the immutable boundary input and syntactic validation. |
| `Response.java` | Define the immutable, allowlisted output without domain or persistence types. |
| `Policy.java` | Declare the route's authorization requirement and deny when no evaluator permits it. |
| `Handler.java` | Orchestrate one use case and own its single reactive transaction boundary. |
| `Queries.java` | Declare the slice-local persistence port; every tenant-scoped method carries the tenant identifier. |
| `SliceTest.java` | Verify policy and handler behavior at the slice boundary with test doubles or in-memory ports. |

Do not add a shared controller, service, or repository layer. Domain invariants
remain in the module's `domain` package. Cross-module calls use only an allowed
`<module>::api` contract, and asynchronous propagation uses `OutboxWriter`.
Mutating handlers emit through `AuditEmitter` inside the business transaction.
The reactive request path must not call `block()` or introduce blocking work on
an event-loop thread.

## Conformance Rules

Every slice is subject to the [`R1-R10 rule card`](conformance-rules.md).
The foundation feature enforces R1-R8 in blocking CI stage 4:

| Rule | Authoring consequence |
|---|---|
| R1 | Nothing outside a slice imports a type owned by that slice. |
| R2 | Cross-module imports target only a declared, exported `api`. |
| R3 | A query references only relations in its owning module's schema. |
| R4 | The handler owns exactly one transaction and never invokes another handler. |
| R5 | Every tenant-scoped query method accepts the tenant identifier. |
| R6 | Domain code imports only domain/JDK types and follows the shared clock and exact-decimal rules. |
| R7 | Cross-module state propagation uses the outbox or an enumerated `ADR-023` atomic flow. |
| R8 | Every mutating handler emits an audit event in its transaction. |

R9 and R10 are runtime database-role controls owned by `FEAT-PLAT-002`; later
persistence slices must comply once that feature installs them.

## Worked Example

The shipping reference is
[`platform/slice/getConformanceReference`](../src/main/java/org/meldtech/platform/platform/slice/getConformanceReference).
It contains the six production files, an inward-facing `Queries` port, an
in-memory adapter, and a matching
[`SliceTest`](../src/test/java/org/meldtech/platform/platform/slice/getConformanceReference/SliceTest.java).
It intentionally contains no business behavior.

Use the example for package shape and dependency direction. Do not copy its
operator-only policy or in-memory persistence choice into a business slice;
define those from the use case's authorization and data requirements.

## Local Verification

Run commands from the repository root:

```bash
# Fast slice tests and the mandatory SliceTest-presence check
./gradlew sliceTest verifySliceTests

# R1-R8 architecture and module checks
./gradlew conformanceTest

# The same blocking entry point used by CI stage 4
ci/stage-4

# Docker-free compilation and static source checks
./gradlew compileJava compileTestJava compileConformanceTestJava ciStage3
```

Run a focused slice test while iterating:

```bash
./gradlew sliceTest --tests \
  'org.meldtech.platform.<module>.slice.<verbNoun>.SliceTest'
```

Before review, run `./gradlew test` when Docker is available because the current
full application context test uses PostgreSQL Testcontainers. A conformance
failure is blocking; fix the dependency, transaction, query, domain, propagation,
or audit structure identified by its stable `R<n> ... violated:` diagnostic.
