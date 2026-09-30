# Integration Event Delivery Contract

Status: normative contract for every producer and consumer using the platform
integration topology.

Authority: `FEAT-PLAT-004` P2.7, `ADR-009`, architecture sections 11.2-11.4,
and `TASK-PLAT4-DEFECT-006`.

## Guarantee

The platform provides **at-least-once, no ordering guarantee, idempotent
apply on the business key, version-guarded** delivery.

- A committed business change and its outbox row are atomic. Publication can
  be delayed, repeated, or observed after later events; it is never coupled to
  the originating request.
- `outbox_event_id` is stable across publication retries and redrives. A
  consumer must assume the same event can arrive more than once, including
  after its first effect committed.
- No global, per-context, per-tenant, or per-aggregate arrival order is
  promised. Prefetch 32 buffers deliveries and is not an ordering mechanism.
- A consumer declares the exact `<context>.<Event>.v<n>` types it handles.
  An unknown version is dead-lettered as `UNHANDLED_EVENT_VERSION`; it is
  never silently acknowledged or interpreted as a nearby version.

## Required Consumer Transaction

For every delivery, the consumer opens one transaction under its own module
role and tenant context. In that transaction it:

1. checks and inserts the module-local `processed_event` guard for the stable
   `outbox_event_id`;
2. checks the operation's section 14.6 business key;
3. applies the effect only when both guards admit it;
4. persists the handled aggregate/event version so an older event cannot
   overwrite newer state; and
5. commits the guard and effect together before acknowledging the broker.

A duplicate id or business key is a successful no-op and is acknowledged. A
stale aggregate version is also an observable no-op. If the transaction rolls
back or the process stops before acknowledgement, the broker may redeliver and
the same algorithm runs again. An in-memory set, delivery tag, queue position,
or timestamp is not an idempotency or version guard.

## Producer And Envelope Rules

The producer registers and validates a JSON Schema before the event can be
committed. The envelope carries `outbox_event_id`, `event_type`, tenant and
aggregate identifiers, correlation id, W3C trace context, occurrence time,
and the schema-validated payload. Credential material is forbidden. Event
type, identity, and causality metadata are immutable across retries.

During an intentional version transition the owner publishes both versions.
Consumers opt into a version explicitly; they do not perform heuristic
up-conversion. Retirement requires 30 consecutive days of zero consumption.

## Ordering Seam

A RabbitMQ consistent-hash exchange keyed by `aggregate_id` is an available
but unused seam. It is not part of the MVP topology and its presence must not
be inferred from the partition-key header.

Adoption requires all of the following: a requirement that cannot be resolved
by an in-transaction aggregate precedence rule; an approved architecture
amendment; a fixed partition count and rebalance procedure; one active
consumer per partition; broker-plugin provisioning and failure testing; and
updated capacity, recovery, and compatibility evidence. Even after adoption,
duplicates remain possible and no global ordering is claimed.
