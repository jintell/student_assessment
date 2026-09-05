# Vertical Slice Template

Status: normative design for `FEAT-PLAT-001` (`P2.5`). Source: architecture §5.1.

```text
src/main/java/org/meldtech/platform/<module>/slice/<verbNoun>/
├── Endpoint.java
├── Request.java
├── Response.java
├── Policy.java
├── Handler.java
└── Queries.java

src/test/java/org/meldtech/platform/<module>/slice/<verbNoun>/
└── SliceTest.java
```

| File | One responsibility |
|---|---|
| `Endpoint.java` | Owns only route binding, credential-to-request-context extraction, input decoding, HTTP status/headers, and delegation to this slice's policy and handler. |
| `Request.java` | Defines the immutable, bean-validated input record and rejects unknown or syntactically invalid input at the boundary. |
| `Response.java` | Defines the immutable output record and exposes no domain, persistence, secret, or answer-key type. |
| `Policy.java` | Declares and evaluates the authorization rule for this use case, with `DENY` as the absence/default outcome. |
| `Handler.java` | Orchestrates this use case and is its single reactive transaction boundary; it calls no other handler. |
| `Queries.java` | Declares this slice's narrow persistence port, with `TenantId` on every tenant-scoped method and no foreign-schema SQL. |
| `SliceTest.java` | Exercises policy and handler behavior with in-memory ports, without starting Spring or reaching infrastructure. |

## Dependency Direction

```text
Endpoint -> Policy -> shared api contracts
Endpoint -> Handler -> domain/domain.policy
                    -> Queries (slice-owned port)
                    -> permitted module::api contracts
                    -> audit::api and, when required, outbox::api
infra adapter ------> Queries
SliceTest ----------> Policy + Handler + in-memory ports
```

Nothing outside `<module>.slice.<verbNoun>` imports a type from that package. Infrastructure implements inward-facing ports through Spring wiring without the slice importing an adapter. Domain types depend only on JDK/domain types and never on Endpoint, Handler, Queries, Spring, R2DBC, Jackson, or infrastructure.

## Construction Rules

1. `Endpoint` and `Handler` are package-private unless framework proxying requires otherwise; request/response visibility is no broader than the generated HTTP/OpenAPI contract needs.
2. `Handler` has exactly one `@Transactional` boundary returning `Mono`/`Flux`; neither endpoint, policy, queries, nor nested calls start another transaction.
3. `Handler` dependencies are constructor-injected ports and published APIs. A handler-to-handler dependency is forbidden.
4. `Queries` extends `TenantScopedQuery`; each tenant-scoped method takes the placeholder tenant carrier first and adopts `FEAT-PLAT-003`'s `TenantId` without changing the port shape.
5. A mutating `Handler` requires `AuditEmitter` and emits at least one audit event before the transaction completes; asynchronous side effects use `OutboxWriter` in the same transaction.
6. `Policy` is a real bean associated one-to-one with the route. A missing or duplicate policy fails startup.
7. Validation is split: bean validation handles syntax in `Request`; aggregates and domain policies enforce business invariants.
8. Responses and logs are allowlisted and never include exception messages, SQL, PINs, OTPs, tokens, answer content, or personal data.

## Rules Satisfied by Construction

| Rule | Structural guarantee |
|---|---|
| R1 | No type outside the slice imports it; the test is colocated in the same package hierarchy. |
| R4 | The handler is the sole transaction owner and cannot depend on another handler. |
| R5 | The queries port extends `TenantScopedQuery` and every tenant method takes the tenant carrier. |
| R6 | Domain sits inward of the slice and its forbidden imports are independently scanned. |
| R8 | Every mutating handler declares `AuditEmitter` and emits before its transaction publisher completes. |
