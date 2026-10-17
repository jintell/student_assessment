# Asynchronous Propagation and ADR-023

Normative FEAT-PLAT-004 scope (f) rule for slice authors: cross-module commands and outbound side effects use an outbox intent in the originating business transaction, unless the exact synchronous flow is on ADR-023's closed list. Convenience, latency preference or existing database access is not an exception.

## Decide at the Slice Boundary

1. A local business write stays under its module role and tenant context.
2. An asynchronous cross-module/outbound effect uses the shared `OutboxWriter` port, not RabbitMQ/Kafka/HTTP publishing from the request transaction. Follow the [producer](event-authoring.md) and [consumer](consumer-authoring.md) guides. Read-only cross-module queries still follow explicit module APIs and grants; this rule does not authorize reaching into another module's tables.
3. Only `examaccess.verifyPinAndStartAttempt` (`AtomicCrossModuleFlow.EXAM_ENTRY`) is presently enumerated for synchronous cross-module mutation. The declared `SynchronousAtomicFlow` and `CrossModuleCommandApi` boundary must match that exact operation. An annotation cannot turn an unrelated workflow into exam entry.
4. Execute that approved flow in one reactive transaction using `app_txn_examentry`, with tenant context and the controlled role-selection boundary. The role has explicit narrow table grants, not module-role membership or arbitrary schema access. Its [grant matrix](grant-matrix.md#exam-entry-composite-role) is authoritative. Preserve PIN-consumption/attempt-start atomicity, audit and applicable outbox intent together.
5. Answer acceptance is a separate ADR-009 exclusion: **no outbox insert on the answer-acceptance path**. It does not permit a direct external side effect or an extra composite role.

## Why the List Is Closed

Adding a new flow requires an approved architecture change, a requirement for cross-module atomicity, explicit narrower-than-union grants, isolation and rollback proofs, and updates to the enumerations/matrix/conformance tests. Do not add a runtime-configured exception, broaden worker memberships, borrow `app_migrator`, execute `SET ROLE` ad hoc or suppress a test. Otherwise ADR-023 becomes an unbounded alternative to asynchronous propagation and erodes module ownership (ARC-RISK-022).

## Enforced Evidence

`R7OutboxPropagationTests` checks the closed flow enumeration, reviewed command boundaries and direct broker dependencies outside the outbox adapter; its invalid direct-write fixture must fail. `AnswerAcceptanceOutboxTests` rejects an outbox dependency in the recognized answer-acceptance path, including its negative fixture. These are CI stage 4 conformance rules, not proof that every future request call chain is correct.

`CompositeRoleNarrownessVerifier` and the PostgreSQL grant audits check the exact approved role privileges and denials against live catalogues. Runtime integration must prove transaction/role behavior; successful static conformance does not replace it. `ARC-VERIFY-006` is the relevant composite-write integration limb. Event compatibility and idempotency inventory currently have local gate names, not invented architecture verification IDs; the plan's `ARC-VERIFY-012/013/014` attribution is tracked as TASK-PLAT4-DEFECT-005.

```bash
./gradlew conformanceTest --tests '*R7OutboxPropagationTests' --tests '*AnswerAcceptanceOutboxTests'
./gradlew integrationTest --tests '*PersistenceSecurityGatesIntegrationTest'
```
