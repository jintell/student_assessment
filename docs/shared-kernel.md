# Shared Kernel Ownership

Status: normative component-to-feature ownership map for architecture section
8.4 and the related idempotency concern from section 10.5.

The shared kernel is the innermost application boundary. Code below
`org.meldtech.platform.shared.kernel` contains framework-neutral values and
ports only; Spring, Reactor, R2DBC, Jackson, Redis, feature modules, and
infrastructure types remain outside it. A feature that needs external behavior
depends on a kernel port, while the owning adapter depends inward on that port.

## Ownership Map

| Component or concern | Responsibility | Owner and implementation task |
|---|---|---|
| Typed identifiers (`TenantId`, `CandidateId`, `AttemptId`, and peers) | Prevent identifier confusion through incompatible value types | `FEAT-PLAT-003` `P4.1` |
| `ActorContext` | Carry attributable workforce, candidate, or enumerated system identity | `FEAT-PLAT-003` `P4.2` |
| `RequestContextPropagation` | Carry correlation, actor, and tenant context across the reactive request path | Mechanism: `FEAT-PLAT-001` `P4.7`-`P4.9`; definitive payload and adoption: `FEAT-PLAT-003` `P4.15`-`P4.17` |
| `Clock` | Provide the sole server-time source | Port: `FEAT-PLAT-003` `P4.3`; production adapter: `platform.infra.time.SystemClock`; conformance: `P4.19` |
| `OutboxWriter` | Append an integration event in the caller-owned transaction | Port: `FEAT-PLAT-003` `P4.5`; persistence and relay: `FEAT-PLAT-004` |
| `AuditEmitter` | Append an attributable, hash-linked audit event in the caller-owned transaction | `FEAT-AUD-001`; excluded from `FEAT-PLAT-003` |
| `ProblemDetailMapper` | Select and construct allowlisted RFC 9457 responses | Contract: `FEAT-PLAT-003` `P4.10`-`P4.12`; WebFlux adapter: `platform.infra.kernel.error` |
| Decimal conventions | Define exact arithmetic context, storage bounds, and canonical whole-number rounding | Primitive: `FEAT-PLAT-003` `P4.4`, `P4.20`; scoring pipeline: `FEAT-GRD-001` |
| `TransactionalCollaboration` | Execute an enumerated synchronous cross-module flow atomically | `FEAT-PLAT-002` `P4.7`; consumed by `FEAT-EXAM-007` |
| `SecurityContextInitializer` | Install transaction-local role and tenant context and reset pooled connections | `FEAT-PLAT-002` `P4.2`-`P4.5`; definitive tenant-type adoption: `FEAT-PLAT-003` `P4.17` |
| `TenantScopedQuery` | Mark query contracts that must accept tenant identity | `FEAT-PLAT-001` `P4.5`; definitive tenant-type adoption: `FEAT-PLAT-003` `P4.16` |
| `IdempotencyStore` | Reserve or replay eligible non-durable `POST` transitions for 24 hours and fail closed when unavailable | Port and Redis adapter: `FEAT-PLAT-003` `P4.6`-`P4.9`; production Redis provisioning remains unowned under `TASK-PLAT3-DEFECT-005` |

## Kernel Deliveries

`FEAT-PLAT-003` owns six cohesive delivery groups: identity and actor context,
controlled time, exact decimal conventions, the outbox port, the problem-detail
contract, and the idempotency port. It adopts the existing context-propagation
mechanism rather than defining a second one.

`TenantScopedQuery`, `TransactionalCollaboration`,
`SecurityContextInitializer`, and `AuditEmitter` are deliberately outside the
kernel feature's ownership. Consumers must depend on their published API or
kernel port and must not import another feature's infrastructure or slice
implementation.
