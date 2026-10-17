# Consumer Authoring

Normative guidance for FEAT-PLAT-004 P10.3. Every consumer follows the [delivery contract](../contracts/events/DELIVERY-CONTRACT.md): at-least-once, no ordering guarantee, idempotent apply on the business key, version-guarded. An event ID alone is not the business key.

## Register and Migrate

Declare exact handled versions in the consumer adapter and `contracts/events/consumers.yaml`. Keep schema ownership and enum-default proving tests aligned with the [producer guide](event-authoring.md). `VersionAwareEventDispatcher` dispatches only registered versions; an unknown version becomes `UNHANDLED_EVENT_VERSION` with a distinct alert path. It is never decoded as a nearby version or silently acknowledged. The remedy is a compatible consumer deployment; see [DLQ recovery](runbooks/integration-dlq.md).

Create `processed_event` in the consuming module's own schema through a new Flyway migration, using `src/main/resources/db/templates/processed_event.sql`. Preserve the unique stable `outbox_event_id`, tenant ID, event type, processed time and bounded outcome. Enable and FORCE RLS with the module-role tenant predicate. Module consumers must not read a shared guard from `outbox` or receive relay privileges. Resolve schema names from the closed consumer registration, never message input. Do not change an applied migration.

`ProcessedEventGuard`, `EventConsumer` and the dispatcher are package-private infrastructure primitives, not public business APIs. Keep their assembly within platform infrastructure through an approved adapter; do not expose them or import feature internals to bypass module boundaries. Current production assembly is not delivered by merely creating a consumer class.

## One Reactive Transaction, Then Acknowledge

1. Establish the consumer's module role and validated tenant/actor context and start its reactive database transaction.
2. Reserve the stable event ID in that module's `processed_event`. `ProcessedEventGuard.applyOnce` rejects a missing transaction and uses `INSERT ... ON CONFLICT ... DO NOTHING`. A conflicting event ID returns `DELIVERY_DUPLICATE` without invoking the effect.
3. In the effect, enforce the operation's architecture §14.6 business key using a database constraint or atomic state guard. Two different event IDs can represent the same business action; the second must become `BUSINESS_DUPLICATE`, not a second effect. The primitive does not invent this business constraint for you.
4. Apply an atomic aggregate revision/state predicate so an older delivery cannot overwrite newer state. Persist the accepted version with the effect; return `STALE_VERSION` for a stale event. Do not substitute arrival time or broker queue position for a domain revision.
5. Return exactly one nonempty outcome (`APPLIED`, `BUSINESS_DUPLICATE`, or `STALE_VERSION`) from the effect. `DELIVERY_DUPLICATE` is reserved for the event-ID guard. Compose the guard, business effect and version update into the same transaction; rollback must remove both the reservation and any effect.
6. Acknowledge the broker only after successful commit, including committed duplicate/stale no-ops. On failure/cancellation use the approved retry/dead-letter policy, never acknowledge lost work. A crash after commit but before acknowledgement is expected to redeliver safely.

Never use an in-memory set/cache, broker delivery tag, timestamp or process-local lock as the durable guard: restarts, multiple replicas and crashes defeat it. Do not wrap an irreversible HTTP/provider effect in a database transaction and call it atomic. External dispatch needs its owning feature's durable intent, business identity and provider reconciliation protocol; the generic primitive proves database effects only.

## Required Proofs

Add real PostgreSQL tests for duplicate event ID, distinct event IDs sharing a business key, rollback before commit, redelivery after commit, out-of-order revisions, concurrent deliveries and tenant isolation. Use the shared integration infrastructure; do not replace PostgreSQL with an in-memory dialect. Include unknown-version routing and successful handling for every supported version. Demonstrate that a duplicate never calls the effect and a stale version never overwrites current state.

Reference evidence: `OutboxFailureInjectionIntegrationTest` covers commit/crash/duplicate and out-of-order behavior; `ProcessedEventGuardTest` checks transaction and SQL contracts; `VersionAwareEventDispatcherTest` checks version dispatch. These are infrastructure proofs, not proofs of a future feature's business key. Keep the operation owner and proving-test mapping in `contracts/idempotency-inventory.yaml`; feature owners remain responsible for their own integration tests. Use bounded metric labels and restricted correlation/trace references, never payload logging.
