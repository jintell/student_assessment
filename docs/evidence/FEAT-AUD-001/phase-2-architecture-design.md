# FEAT-AUD-001 Phase 2 Architecture and Design

Date: 2026-10-07
Architecture baseline: `arch-v1.4`
Scope: audit tasks `P2.1`-`P2.20`

This document fixes the design contracts that Phase 3 infrastructure and
Phase 4 implementation must follow. It does not claim those later phases are
implemented. The approved records under `ci/dor/FEAT-AUD-001` are normative
inputs; a conflicting change requires the approvals named by those records.

## P2.1 Audit Layering

Audit follows clean architecture: capability code depends on stable inward
ports, while database, KMS, scheduling, and HTTP concerns remain adapters.

| Layer | Responsibility | Permitted dependencies | Forbidden dependencies |
|---|---|---|---|
| `shared.kernel.audit` | `AuditEmitter`, `AuditEvent`, `RetentionClass`, and `EntityRef`; the framework-free contract used by all capabilities | JDK, other kernel value types, and Reactive Streams `Publisher` | Spring, Reactor, R2DBC, SQL, JSON libraries, KMS SDKs, and `audit.*` |
| `audit.domain` | Canonical codec, hashing, shard assignment, epoch identity, root/seal derivation, and verification algebra as pure functions | JDK and `shared.kernel` values | Spring, Reactor, R2DBC, SQL, Jackson, KMS SDKs, clocks, and mutable global state |
| `audit.slice.<verbNoun>` | Use-case orchestration, authorization `Policy`, request/response contracts, and narrow inward ports | `audit.domain`, `shared.kernel`, and published module APIs | `audit.infra` and another feature's private packages |
| `audit.infra` | R2DBC persistence, transactional emitter, KMS signer, verifier data access, scheduler, and runtime wiring | Inward audit contracts and approved framework/vendor APIs | Capability business decisions or another module's private packages |

The compliance query is the vertical slice
`audit.slice.getComplianceAuditEvents`; its adapter implements slice-owned
persistence ports. No module outside `audit` may reference `audit.domain`,
`audit.slice`, or `audit.infra`. Other modules emit only through
`shared.kernel.audit.AuditEmitter`, so adding audit support never reverses the
module dependency direction.

The provisional `org.meldtech.platform.audit.api.AuditEmitter` and
`AuditEvent` are architecture drift left by the scaffold. Phase 4 task `P4.1`
must replace them with the kernel contracts and update R8; this design does
not preserve that package as a compatibility surface.

Blocking conformance rules are:

| Rule | Enforcement design |
|---|---|
| `AuditKernelPurity` | Compile `shared.kernel` against the framework-free kernel classpath and reject Spring, Reactor, R2DBC, SQL, JSON, KMS, or `audit.*` references in signatures and bytecode. |
| `AuditModulePrivacy` | Extend the R2/module-boundary bytecode rule so only types within the `audit` module may import `audit.domain`, `audit.slice`, or `audit.infra`; a negative fixture imports each forbidden layer. |
| `AuditDomainPurity` | Reject framework/vendor imports, I/O, clocks, randomness, environment access, and mutable statics below `audit.domain`; domain functions receive every input explicitly. |
| `AuditSliceBoundary` | Enforce R1-R6: the compliance slice is a leaf, owns its ports, has one handler transaction boundary, and never imports infrastructure. |

These rules run in blocking CI stage 4. Spring Modulith's closed `audit`
module remains a second boundary check, not the sole proof.

## P2.2 Audit Event and Emitter Contract

The kernel contract has one emission operation and no convenience overload:

```java
Publisher<Void> emit(AuditEvent event, ActorContext actor, Instant occurredAt);
```

`Publisher` is the Reactive Streams interface already admitted by the kernel
compile boundary. Implementations may return a Reactor `Mono`, but Reactor is
absent from the port. Completion means both database statements have executed
on the caller's transaction; an error is propagated unchanged to that
transaction.

`AuditEvent` is an immutable final record with required constructor
components: `eventType`, `EntityRef entity`, a non-empty closed set of
retention candidates expressed with `RetentionClass`, and `payload`. Its payload uses a nested,
sealed, framework-free canonical value algebra for object, array, string,
integer, decimal, boolean, and explicit null values. Maps are defensively
copied, keys are non-blank and unique, floating-point values and arbitrary
`Object` instances cannot be represented, and a null Java reference is
rejected. This gives the codec a closed input model without importing a JSON
library into the kernel.

`EntityRef` requires a registered bounded-context entity type and one stable,
non-blank opaque identifier. That identifier is both the business reference
and the shard-assignment input; event types cannot substitute actor, tenant,
email, or display-name values. `RetentionClass` is the closed set:
`RESULT_CORRECTION_EVIDENCE`, `RESULT_PUBLICATION_EVIDENCE`,
`PIN_SECURITY_EVENT`, and `GENERAL_AUDIT_EVENT`. The emitter resolves those
candidates against the active policy and adds the applied non-secret policy
key and positive version to the persisted canonical envelope.

Attribution is never inferred from thread locals, token claims, client
payloads, or database defaults. The required `ActorContext` argument supplies
`actor_type`, `actor_id`, the explicitly present-or-absent `tenant_id`,
`correlation_id`, and the explicitly present-or-absent enumerated
`system_actor_name`; its constructor enforces actor consistency. The required
`occurredAt` comes from the kernel controlled clock and is passed explicitly.
The emitter validates that tenant absence is permitted only for an enumerated
platform-scope event type. There is no no-argument, actor-free,
tenant-defaulting, ambient-clock, or fire-and-forget emission path.

## P2.3 Audit-Event Catalogue Convention

Every identifier has the immutable form
`<bounded-context>.<EVENT_CODE>.v<major>`:

- `bounded-context` matches `^[a-z][a-z0-9]*$` and is the owning Spring
  Modulith module identifier;
- `EVENT_CODE` matches `^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$`, describes a
  completed fact in past tense where practical, and is globally stable; and
- `major` is a positive integer. A semantic or payload compatibility break
  allocates a new major; an issued identifier is never renamed, reused, or
  given a different meaning.

The middle `EVENT_CODE` token is exactly the `eventCode` used by
`FEAT-OBS-001`; case conversion and aliases are forbidden. Audit emission and
business-metric recording describe the same fact but remain separate calls
and stores. A metric is not audit evidence, and an audit event is not an
operational log.

The owning capability registers an event before its first emission in the
generated catalogue source. One entry contains `event_type`, owner module,
purpose, fixed retention class or retention-selection rule, payload schema
version, matching `eventCode`, entity-reference type, whether tenant absence
is permitted, and whether the event represents a privileged read. Startup
rejects duplicate identifiers, unknown modules or retention classes,
unregistered emissions, token misalignment, and incompatible duplicate
schemas. CI generates the normative documentation and fails when source,
generated catalogue, and emitted constants diverge.

Later features add entries through their own reviewed change, nominate their
payload owner, pass the no-secret schema gate, and add positive catalogue and
retention-classification tests. Registration does not grant permission to
emit: the slice `Policy`, actor attribution, transaction, and R8 obligations
still apply. The initial convention is the approved `P0.8` contract; the
published catalogue artifact itself remains a Phase 10 deliverable.

## P2.4 Canonical JSON Version 1

`AUDIT_CANONICAL_JSON_V1` is a frozen byte grammar selected by
`hash_algo_version = 1`. The version is stored on every event, checkpoint,
and seal that depends on it. Verification dispatches by that stored version;
a byte-affecting change allocates a new positive version and never updates or
re-hashes an existing row.

The version-1 grammar is:

1. Encode exactly one compact JSON value with no whitespace or trailing
   newline. Object keys are NFC-normalized, rejected if normalization creates
   a duplicate, and sorted by unsigned lexicographic order of their UTF-8
   bytes at every nesting level.
2. Normalize every key and string value to Unicode NFC, then encode as UTF-8.
   Escape quotation mark and reverse solidus with a reverse solidus. Encode
   U+0000-U+001F as lowercase `\u00xx`; no short control escapes are used.
   All other characters are emitted as their UTF-8 bytes without ASCII
   escaping. Unpaired surrogates are invalid.
3. Render an integer in base 10 with no leading plus sign or leading zero;
   zero is `0`. Render a decimal without exponent notation, unnecessary
   leading or trailing zeros, or negative zero, while retaining one
   fractional digit so decimal `1.0` remains distinct from integer `1`.
   Binary floating-point input is invalid. Rendering is locale-independent.
4. Render every instant in UTC as `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`, exactly
   six fractional digits. Values outside PostgreSQL's microsecond precision
   are rejected rather than rounded. JVM default locale and time zone are
   never consulted.
5. Explicit canonical null is the four bytes `null`. An absent optional
   payload member emits no key at all. The fixed event envelope emits nullable
   attribution fields explicitly, so an absent tenant or system actor remains
   distinguishable and reproducible.
6. Reject unknown canonical value variants, duplicate keys, non-finite or
   floating-point numbers, and configurable serializer features. The codec
   is an `audit.domain` implementation over the closed kernel value algebra,
   not the application's object mapper.

The versioned golden set is
`config/audit/canonical-json-v1-golden-vectors.json`. Each vector freezes the
typed-input intent, exact canonical UTF-8 bytes in hexadecimal, and SHA-256
of those bytes. Phase 4 codec tests must consume this file directly under at
least two JVM locale/default-time-zone combinations. Review may add vectors;
changing an existing version-1 byte or digest is an architecture change, not
a test update.

## P2.5 Per-Table Grants and Append-Only Trigger

Privileges are table-specific and additive to the Phase 0 default `INSERT`
bootstrap. No role receives schema-wide `UPDATE` or `DELETE` on `audit`.

| Relation | Principal | Granted operations | Explicit denials |
|---|---|---|---|
| `audit.audit_event` and its partitions | Approved module and composite roles | `INSERT` | `UPDATE`, `DELETE`, `TRUNCATE`, trigger bypass, and ownership |
| `audit.audit_chain_head` | Approved module and composite roles | `SELECT` for the keyed lock and `UPDATE` for the conditional advance | `INSERT`, `DELETE`, `TRUNCATE`, and ownership |
| `audit.audit_chain_checkpoint` | Background audit workload | `INSERT` | Application-role access, `UPDATE`, `DELETE`, `TRUNCATE`, and ownership |
| `audit.audit_chain_seal` | Audit sealer | `INSERT`, `SELECT` required by sealing | Application-role writes, `UPDATE`, `DELETE`, `TRUNCATE`, and ownership |
| `audit.audit_chain_root_head` | Audit sealer | `SELECT`, CAS `UPDATE`, and bootstrap `INSERT` through a security-definer provisioning function | Application and composite-role writes, `DELETE`, `TRUNCATE`, and ownership |
| Expired detached event partition | Retention role | Lifecycle operation only after protocol authorization | Membership by every application, composite, sealer, and query role |

The chain-head grant is limited to the module and composite roles already
authorized to emit; future roles are not covered by a name pattern. Head rows
are created only by the approved provisioning identities. This narrower
grant supersedes the chain-head row of the signed `P0.4` decision through the
signed `ADR-011A` amendment; every other `P0.4` grant and denial remains in
force. The sealer, retention, compliance-reader, and migration identities are distinct,
non-inheriting workload roles. Object ownership stays with the migration
owner. `FEAT-PLAT-002` assertions must compare relation-level privileges and
retain the default-privilege proof that new tables start at `INSERT` only.

`audit.audit_event` alone receives a `BEFORE UPDATE OR DELETE FOR EACH ROW`
trigger whose owner is not an application role and whose function always
raises a stable integrity exception. Every event partition inherits or is
given the same protection as part of creation and is checked before attach.
The migration and test enumerate every partition so a child cannot become a
write bypass.

This scope is a correctness condition: a schema-wide mutation trigger would
reject the mandated `audit_chain_head` advance and root-head compare-and-swap,
making every audited transaction or seal fail. Conversely, writable anchor
tables are not evidence-row exceptions; their operations, predicates, roles,
and immutable seal/checkpoint relations are narrowly designed and verified.

## P2.6 Pure Chain Algebra

Every operation below is a total, side-effect-free function of explicit byte
or value inputs. It performs no I/O, clock access, database lookup, signing,
or global configuration read.

`recordHash(previousHash, event)` returns
`SHA-256(previousHash || canonicalJsonV1(event))`. `previousHash` must be
exactly 32 bytes and the canonical codec is selected by the event's positive
`hash_algo_version`; unknown versions fail closed. The canonical envelope
includes event type, entity reference, actor fields, tenant field, controlled
occurrence time, correlation identifier, retention class, applied policy,
payload, epoch period, shard id, and sequence. Thus moving otherwise valid
bytes to another chain changes the hash.

`shardFor(entityRef, shardCount)` hashes the bytes
`UTF8("meldtech.audit.shard.v1\0") || UTF8(entityType) || 0x00 ||
UTF8(entityId)` with SHA-256, interprets the first eight digest bytes as an
unsigned big-endian integer, and returns its unsigned remainder modulo
`shardCount`. `shardCount` is the tenant's positive provisioned value, 64 by
default, and is fixed for the epoch. Java `hashCode`, database collation, and
signed remainder are forbidden.

`epochOf(retentionClass, occurredAt)` returns the pair
`(retentionClass, UTC year-month)`. The period is the seven ASCII bytes
`YYYY-MM`; local time zones and locale calendars cannot affect it.

For sequence one, `previousHash` is the chain seed:

```text
SHA-256(
  UTF8("meldtech.audit.chain.seed.v1\0") ||
  canonicalJsonV1(tenant_id, retention_class, period, shard_id, shard_count)
)
```

The seed is stored as the first row's `prev_hash`, is reproducible without a
database secret, and domain-separates tenants, epochs, shard topology, and
shards. Later records use the immediately preceding committed `record_hash`.
The append adapter must serialize predecessor selection before invoking this
algebra; the exact transaction protocol is fixed in `P2.16`.

## P2.7 Epoch-Seal Derivation

The seal input is an immutable `EpochSealMaterial`: tenant, retention class,
UTC period, shard count, hash-algorithm version, previous tenant root hash,
next root sequence, and exactly one entry for every shard id from zero through
`N - 1`. Entries are sorted numerically by shard id and carry `recordCount`,
optional `seqStart`, optional `seqEnd`, and the terminal head hash. A non-empty
entry requires `recordCount > 0`, `seqStart = 1`, `seqEnd = recordCount`, and a
32-byte head. An empty entry requires count zero and absent sequence bounds.

An empty shard's 32-byte head is not an empty string or omitted member. It is:

```text
SHA-256(
  UTF8("meldtech.audit.empty-shard.v1\0") ||
  canonicalJsonV1(tenant_id, retention_class, period, shard_id, shard_count)
)
```

`per_shard_counts` is a canonical array of exactly `N` unsigned counts whose
array index is the shard id; zero is retained. The same ordered shard array in
the root material contains each shard id, count, sequence bounds or explicit
nulls, and terminal or sentinel hash. Missing, duplicate, or out-of-range
shards fail derivation.

The epoch root is:

```text
SHA-256(
  UTF8("meldtech.audit.epoch-root.v1\0") ||
  previous_root_hash ||
  uint64be(root_seq) ||
  canonicalJsonV1(epoch identity, hash version, shard count, ordered shards)
)
```

The all-zero 32-byte value is the predecessor only for a tenant's first root;
subsequent roots use the previous signed seal's `epoch_root`. The stored seal
contains every derivation input plus `per_shard_counts`, sequence ranges, KMS
signature reference, signature bytes, and signing time. Its signature covers
the domain label, `root_seq`, and `epoch_root`, preventing reuse in another
sequence or protocol. These byte rules make a sparse epoch deterministic
across independent implementations.

## P2.8 Root Compare-and-Swap Append

Tenant provisioning creates one root-head row at sequence zero with the
all-zero predecessor. A sealer appends exactly one closed epoch per database
transaction using these four steps:

1. Read the tenant's observed `root_seq` and `root_head_hash`; absence is a
   provisioning error, not permission to invent an in-memory head.
2. Re-read the closed epoch's immutable shard heads and counts, set
   `nextRootSeq = observedRootSeq + 1`, and derive the epoch root from those
   inputs and the observed predecessor.
3. Ask the audit-sealer KMS identity to sign the versioned signature message.
   No database transaction or root-head lock is held during the remote call.
4. In one transaction insert the immutable seal, then execute
   `UPDATE audit.audit_chain_root_head ... WHERE tenant_id = :tenantId AND
   root_seq = :observedRootSeq AND root_head_hash = :observedRootHash`.
   Exactly one affected row commits both changes.

Zero affected rows means another sealer won. The transaction, including its
seal insert, rolls back. The loser increments the bounded retry metric,
re-reads the winning head and current epoch state, re-derives a different root
from that predecessor, obtains a new signature, and attempts a fresh
transaction. It never updates an existing seal, selects a preferred fork, or
repairs history. A retry limit or deadline leaves the epoch unsealed and
alerts; it does not weaken the predicate.

The scheduler selects eligible epochs by ascending UTC period, then the
approved retention-class order:
`RESULT_CORRECTION_EVIDENCE`, `RESULT_PUBLICATION_EVIDENCE`,
`PIN_SECURITY_EVENT`, `GENERAL_AUDIT_EVENT`. Only one epoch is submitted by a
given worker at a time and only one seal is committed per transaction. The
ordering makes restarts deterministic, while the CAS remains authoritative
under multiple workers. Database uniqueness on `(tenant_id, root_seq)` and on
the epoch identity is a backstop; the verifier independently requires a
dense, duplicate-free sequence reproducible from each predecessor.

## P2.9 Checkpoint Policy

Checkpoints are per `(tenant_id, retention_class, period, shard_id)`. The
background checkpoint worker creates one when the uncheckpointed tail reaches
10,000 committed records or the oldest uncheckpointed record reaches one
hour, whichever occurs first. Closing an epoch always creates a terminal
checkpoint for every non-empty shard even when neither threshold was reached;
empty shards are represented by the seal sentinel and need no checkpoint.

Each checkpoint records chain identity, `seq_start`, `seq_end`, terminal head
hash, record count, hash-algorithm version, observed event time range, signing
key version, KMS signature, and trusted `signed_at`. The signature message is
domain-separated and includes every stored field except the signature bytes.
A uniqueness constraint on chain identity plus `seq_end` makes retries
idempotent, and sequence bounds must advance monotonically without exceeding
the current committed head.

The worker obtains a stable committed head, derives and verifies the tail,
then signs off the business-write path. If the head advances concurrently,
the checkpoint remains a valid prefix. At epoch close the sealer waits for or
creates the terminal checkpoint before deriving the seal. KMS or persistence
failure leaves the prior checkpoint valid, increments overdue state once the
policy bound is crossed, and never blocks an audited business write.

Only the audit checkpoint workload identity can invoke the asymmetric signing
operation or insert a checkpoint. Application roles cannot call that key,
insert a checkpoint, choose `signed_at`, or alter a signed row. `signed_at`
comes from trusted service/database time and is covered by the signature;
the KMS audit record and returned key version are retained. Consequently an
application compromise cannot forge a checkpoint retrospectively or present
one under an earlier signing identity.

## P2.10 KMS Signing Boundary

The signer is a narrow outbound port accepting only a versioned checkpoint or
epoch-root signature message and returning signature bytes, immutable key
version, algorithm, and provider request identifier. The domain constructs
the message; the adapter cannot accept arbitrary objects, timestamps, or
payload maps. Verification uses the retained public key and does not require
sign permission.

The same worker image runs the scheduled signer under the dedicated
`cbt-audit-sealer` workload identity. Only deployments with the audit-sealer
capability receive that identity; `cbt-api`, ordinary `cbt-worker`,
`cbt-pindist`, migration, and retention identities receive an explicit deny.
The private asymmetric key is non-exportable and used only through KMS
`Sign`; it is distinct from the symmetric PIN-retrieval KEK and every token or
notification key. The sealer deployment has no public ingress and can reach
only PostgreSQL, KMS, telemetry, and required identity endpoints.

The key policy permits the sealer identity to `Sign`, read public-key
metadata, and describe the key. It denies decrypt, encrypt, key export,
policy mutation, disable, deletion scheduling, and delegation. Administrative
key lifecycle actions belong to a separate dual-approved security role and
cannot sign. KMS audit logs, database signature references, workload identity,
and trusted signing time are retained so neither application code nor a key
administrator can manufacture an apparently historical application
checkpoint alone.

This adds the missing section 17.5 secret-table entry:

| Secret/key | Scope | Rotation |
|---|---|---|
| Audit checkpoint and epoch-root signing key | Per environment, dedicated non-exportable asymmetric KMS key; `Sign` only for `cbt-audit-sealer`; explicit deny for API, ordinary worker, PIN-distribution, migration, and retention identities | Annual and immediately on suspected disclosure or signer offboarding; old public-key versions retained for the longest evidence lifetime |

Under `ARC-SEC-013`, no private key material, credential, key policy, or raw
provider error is stored in source, images, configuration maps, logs, audit
payloads, or database rows. Startup validates key id, asymmetric algorithm,
enabled state, environment ownership, and workload grant. A mismatch prevents
the sealer from becoming ready but leaves business writes available. Rotation
changes the recorded key version; it never re-signs an existing checkpoint or
seal.

## P2.11 Write-Time Retention Resolution

`FEAT-PRIV-001` publishes a framework-free `RetentionPolicyView` API. Given
the controlled occurrence instant, event type, entity type, and declared
retention candidates, it returns one immutable `RetentionDecision` containing
the policy key, positive append-only policy version, effective interval, all
applicable class horizons, and the winning class. The implementation serves
an atomically refreshed, approved policy snapshot and performs no database or
network operation on the audit hot path.

The emitter calls this view after catalogue validation and before canonical
serialization. It independently verifies that the returned version was
effective at `occurredAt`, every catalogue-required candidate was evaluated,
and the winner has the latest retention horizon. An indefinite result-lifetime
obligation outranks any dated horizon. Equal horizons use the stable class
precedence `RESULT_CORRECTION_EVIDENCE`,
`RESULT_PUBLICATION_EVIDENCE`, `GENERAL_AUDIT_EVENT`, then
`PIN_SECURITY_EVENT`; this tie-break chooses physical placement only and never
shortens retention.

The winning `retention_class`, policy key, policy version, and resolved
horizon semantics enter the canonical event and are inserted on the row.
They are not recalculated by a query, verifier, or disposition worker.
Unknown, stale, incomplete, unavailable, or internally inconsistent policy
views fail emission and therefore fail the caller's business transaction.

Policy change cannot mutate an existing audit row. When approved governance
requires a different class for retained evidence, the retention engine emits
the registered `privacy.RETENTION_RECLASSIFIED.v1` event referencing the
original entity/event, prior placement, new applicable policy key/version,
and new class. Existing evidence remains in its original immutable chain;
disposition applies longest-wins across the original placement and the later
reclassification evidence. `FEAT-PRIV-001` owns policy approval and snapshot
publication; `FEAT-AUD-001` owns validation, placement, and stored proof of
the decision.

## P2.12 Retention-Homogeneous Partitioning

`audit.audit_event` is partitioned first by
`LIST (retention_class)` into exactly four class parents, then each parent by
monthly UTC `RANGE (occurred_at)`. A child covers a half-open interval from
the first instant of one UTC month to the first instant of the next. There is
no default partition: an unknown class or missing month fails the transaction
instead of hiding evidence in an ungoverned retention unit.

At the five-year planning horizon the upper bound is `4 * 60 = 240` monthly
leaf partitions. Shorter-lived PIN and general partitions may be detached
earlier but do not change that schema bound. `shard_id` is an ordinary
non-null column and index component, never a partition key; multiplying 240
partitions by 64 shards is prohibited.

The partition-maintenance job runs daily and creates three future UTC months
for all four classes, with an alert when fewer than two future months exist.
Bootstrap and recovery create the current month plus that horizon. Creation
is idempotent and executed by the migration/partition-owner identity, not an
application role. Before attach, the job applies ownership, event-table
append-only trigger, RLS, table grants, check constraints, and the three
tenant-leading indexes, then validates the exact range constraint. Attach is
an expand operation; detach occurs only through the ordered disposition
protocol.

Every leaf is one disposition unit and one epoch for its class/month. Its name
is deterministic, `audit_event_p_<class_token>_yYYYYmMM`, while correctness
comes from catalog constraints rather than name parsing. Anchors are not
children of `audit_event`, use the longest evidence retention, and therefore
survive detaching any event partition.

## P2.13 Integrity Verifier

The verifier has one read-only evidence port and pure domain validators. Its
database role can select audit events, heads, checkpoints, seals, root heads,
and retained public-key metadata but cannot insert, update, delete, detach,
sign, or assume an application, sealer, or retention role. Work is
tenant-bounded and streamed with backpressure; progress checkpoints are
operational state, never audit-chain state.

| Schedule | Verification scope |
|---|---|
| Daily open-chain walk | For every open shard, derive the seed, require dense sequence from one, canonicalize each event by its stored hash version, reproduce every `prev_hash` and `record_hash`, validate checkpoint prefixes, and match the committed chain head. |
| Daily sealed-epoch check | Without reading disposed/retained event rows, reproduce the epoch root from the stored predecessor, root sequence, shard heads, counts, ranges, and sentinel rules; validate the KMS signature and key version. |
| Quarterly full re-walk | Re-read every retained event in every retained epoch, reproduce shard chains, checkpoint signatures, seals, and the complete per-tenant root chain. |
| Post-restore full re-walk | Run the same full verification before the restored environment accepts production writes or disposition work. |

Every run also asserts one seal per epoch, `root_seq` starts at one and is
dense and duplicate-free, each root names the immediately prior root, the
current root head equals the final seal, every root reproduces from recorded
material, every non-empty shard range/count agrees, and no unsupported codec
or signature version appears. Disposed epochs remain verifiable from retained
anchors and disposition events; missing event partitions are accepted only
when that ordered evidence exists.

A mismatch produces an immutable verifier report, high-severity metric and
alert, captures database snapshot/LSN and affected identities, and stops the
affected tenant's sealing and disposition. It never writes a replacement
event, edits a head, re-signs a seal, fills a gap, deletes a branch, or chooses
one fork as authoritative. Investigation works from a protected snapshot;
restoration follows the recovery procedure and is followed by another full
walk. Evidence preservation is part of the verifier API: it exposes findings,
not a repair command.

## P2.14 Ordered Disposition Protocol

`FEAT-PRIV-001` decides that a class/month is eligible under an approved
policy version and supplies an authorized `DispositionRequest` containing the
tenant, epoch, policy key/version, due time, and hold evaluation. The audit
module owns the executor because only it can validate and preserve the chain.
The request is idempotent by epoch identity plus policy version and advances a
durable state machine; no caller can invoke an individual later step.

The executor performs these steps in order:

1. Freeze disposition for the target epoch, take a consistent snapshot, and
   fully verify every shard link, sequence, checkpoint, count, and head.
2. Recompute the epoch root, match the unique stored seal and its predecessor,
   validate the KMS signature/key version, and confirm the seal is present in
   the tenant's dense root chain. An unsealed epoch stops here.
3. Through the normal in-transaction emitter, append
   `audit.AUDIT_EPOCH_DISPOSED.v1` to the current open epoch. Its payload names
   the disposed epoch, sequence ranges, root hash, seal/signature reference,
   applied `policy_key` and version, request id, and authorization evidence.
   Commit and verify that event before changing partition attachment.
4. Re-check that no hold or newer longer policy now applies, then use the
   retention identity to detach the exact leaf partition. Archive or drop the
   detached relation according to the class policy; application roles never
   receive this capability.
5. Re-verify every retained epoch tail for the tenant and reproduce the full
   root chain across the now-absent event epoch using its retained seal,
   checkpoint, and disposition evidence. Only this success completes the
   request.

A concurrent seal is serialized at the root-head CAS and observed afresh in
steps 2, 4, and 5; the executor does not hold a root lock across external or
partition work. Failure records the last successful state and outcome but
never jumps forward. Before step 4, retry starts verification again. After a
committed detach, retry may perform only the recorded archive/drop completion
and step 5; it cannot emit a second disposition event or detach a different
relation. Any skipped, reordered, unverifiable, or ambiguous step is a failed
disposition and raises the integrity alert.

## P2.15 Partition-Granular Hold Suspension

Before eligibility and again immediately before detach, the privacy policy
view returns every active legal hold intersecting the target partition. One
matching row promotes the entire class/month disposition unit from `ELIGIBLE`
to `HOLD_SUSPENDED`; extracting or moving only held events is forbidden
because it would split an immutable chain.

Promotion records the partition identity, original retention start and due
time, hold reference, non-secret legal basis reference, applied policy key and
version, detection time, and actor/request correlation. The executor emits
`audit.AUDIT_EPOCH_DISPOSITION_SUPPRESSED.v1` into the current open epoch and
does not verify for deletion, detach, archive, or drop. Repeated scheduler
runs are idempotent for the same hold/version while still updating operational
age metrics.

Hold details remain owned by `FEAT-PRIV-001`; the audit record contains an
opaque reference rather than personal data or legal narrative. Multiple
holds accumulate, and release requires all applicable holds to be inactive.
Release is itself an authorized audited fact. It returns the unit to policy
evaluation using the original retention start/due time, never the hold-release
time, so suspension cannot silently reset or shorten the lifecycle.

Once all holds are released, an already-expired unit may enter `P2.14` on the
next run and must still pass every verification step. The accepted
over-retention is visible by class, age, hold reference, and policy version to
the DPO/compliance surface. At 90 days of continuous suspension it raises the
required P3 review; that alert does not auto-release, repartition, or bypass a
hold.

## P2.16 In-Transaction Atomic Emission

`ADR-011A` resolves `REV11-ADR-GAP-001` / `REV11-ARCH-REVIEW-001` with
`PRELOCKED_TWO_STATEMENT_APPEND_V1`. Emission joins the caller's connection and
transaction; `REQUIRES_NEW`, a second connection, async execution, and event
listeners are forbidden.

After all business SQL, the connection enters an audit-finalization phase:

1. `SELECT seq, head_hash FROM audit.audit_chain_head ... FOR UPDATE` locks the
   pre-provisioned `(tenant_id, retention_class, period, shard_id)` row and
   returns the immediately preceding committed head. A missing row is a
   provisioning failure. The canonical event bytes are prepared before this
   statement; sequence binding and the pure record hash are finalized after
   the lock.
2. One data-modifying CTE inserts the immutable event, then conditionally
   advances that same locked head from the observed sequence and hash. Exactly
   one updated head is required. Any other row count raises and propagates an
   integrity error so the caller's transaction rolls back.

The lock remains held through commit. A same-shard competitor blocks before
choosing its predecessor and, after the winner commits, reads the winner's
hash. Failure after either statement or before commit leaves neither an orphan
event nor an advanced head. Non-audit SQL after finalization is refused; an
additional registered audit append may use the same protocol, but it cannot
re-open business persistence.

Initial head rows are provisioned by `FEAT-TENANT-001`; future UTC-month rows
are created with the corresponding partitions before writes are eligible.
Lazy first-write creation is forbidden. The final head advance remains after
the event insert while predecessor serialization happens before either
immutable or head state is changed.

Blocking conformance rules assert that the emitter has one caller-owned
transaction and connection, uses only the two approved statement shapes, and
cannot enter async/listener/`REQUIRES_NEW` paths. Integration verification
captures statement order, forces same-shard contention, injects failure after
each statement and before commit, and proves dense sequence, exact predecessor
links, rollback symmetry, and refusal of later business SQL.

Approval:

```text
./ci/verify-audit-atomic-append-approval
AUDIT ATOMIC APPEND APPROVAL: PASS
Predecessor serialization, two-statement budget, grant amendment, and signatures are verified.
```
