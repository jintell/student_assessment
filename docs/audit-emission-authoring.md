# Authoring an audited slice

Use the kernel `AuditEmitter`, `AuditEvent`, `EntityRef`, `RetentionClass` and
`CanonicalValue` contracts. Do not import the closed audit module's domain,
application or persistence implementation. Declare the event using the
[normative catalogue convention](audit-event-catalogue.md) before emitting it.

## Required inputs and attribution

`Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt)`
has no actor-free, ambient-clock or tenant-defaulting overload.

| Input | Why it is required |
|---|---|
| Stable event type and entity type/id | Identify the completed fact and subject; entity identity also fixes shard routing |
| Non-empty retention candidates and canonical object payload | Resolve approved retention before placement/hash; reject secret-bearing or unsupported values |
| Actor type/id | Distinguish a workforce user, candidate and enumerated system identity without anonymous mutations |
| Tenant context | Enforce the institution boundary and bind chain identity; the current emitter rejects tenant absence |
| Correlation ID | Link the business operation to its evidence without treating operational logs as that evidence |
| System actor name | Required exactly for system actors; use an enumerated identity, not an arbitrary service string |
| Source IP in `ActorContext` | Preserve the shared authenticated context contract; it is not a field in the current canonical audit envelope |
| Explicit occurrence instant | Use the controlled clock, with PostgreSQL-compatible microsecond precision; never let a client choose the audit timestamp |

Obtain actor context from the authenticated request or approved job boundary,
not request-body attribution fields. A kernel platform actor is representable
but is not supported by this emitter; do not substitute a fake tenant.
Keep PINs, OTPs, credentials, tokens, signing material and unnecessary personal
data out of payloads. All-environment payload checks are a backstop, not a
replacement for reviewed minimised schemas.

## Compose within the caller transaction

1. Authorise the action and establish the secured reactive caller transaction.
2. Execute the business SQL and prepare explicit event/context/time inputs.
3. Compose `Mono.from(auditEmitter.emit(event, actor, occurredAt))` into that
   transaction's returned publisher, after business SQL and before completion.
4. Propagate every append error so the business transaction rolls back. Return
   a result only through the composed publisher; framework transaction
   completion, not emission completion alone, establishes commit.

The emitter obtains `TransactionalConnection.current()`: first a keyed lock on
the provisioned head, then one event-insert/head-advance CTE. These are exactly
two statements. No business SQL is permitted after audit finalization. Required
head provisioning, immutable retention/shard snapshots and production bean
assembly must exist before a slice can use this path. The answer-save route's
complete six-statement budget is still unproved because that route is absent.

Never call `subscribe()` independently, `block()`, swallow errors, use another
connection, open `REQUIRES_NEW`, or enqueue audit emission for later. There is
no asynchronous audit fallback: it would allow a committed action with no
committed attribution. An outbox event or log line cannot repair that gap.
A privileged read also appends its own audit event in the secured caller
transaction; no page escapes if that append fails.

## What coverage rejects, and what authors must prove

Stage-4 `R8AuditCoverageTests` rejects a non-read-only `@Transactional` method
on a `.slice.` class named `Handler` without a direct call to the real kernel
emitter. A similarly named fake emitter is not sufficient. The rule does not
prove every control-flow branch, catalogue membership, subscription or actual
rollback. It does not automatically cover differently named handlers or
privileged-read methods. Do not exploit those limits to bypass the obligation.

Add tests for successful commit, append failure rolling back business state,
business failure removing audit evidence, explicit attribution, tenant/authority
denial, no-secret rejection and correct retention. Real PostgreSQL concurrency
tests must check exact predecessor links, dense sequence and no orphan/sibling.
Use the [atomic append ADR](decisions/ADR-011A-atomic-audit-append.md) and
[retained P7 tests](evidence/FEAT-AUD-001/phase-7-testing.md) as reference;
passing structural conformance alone is not full audit coverage.
