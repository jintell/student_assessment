# Audit chain topology and proof

Authority: [signed topology contract](../ci/dor/FEAT-AUD-001/P0.8-audit-contract-dor.json),
[atomic append ADR](decisions/ADR-011A-atomic-audit-append.md) and the pure
[audit domain](../src/main/java/org/meldtech/platform/audit/domain).
This describes the implemented algebra and approved operating protocol, not a
claim that production scheduling, provisioning or KMS integration is deployed.

## Sharded writes

A chain is `(tenant, retention class, UTC calendar month, shard)`. The approved
default is 64 shards. Hash the domain-separated entity type/id with SHA-256,
interpret the first eight bytes as an unsigned big-endian integer and take the
remainder modulo the provisioned count. Never use Java `hashCode` or random
routing. FEAT-TENANT-001 must provision heads before tenant writes. The count
is fixed within an epoch; an approved, audited change applies only at a later
boundary after the previous epoch closes. Closed epochs never reopen.

Each shard starts at a domain-separated seed binding tenant, class, period,
shard and count. Its first record has sequence one and that seed as predecessor.
Subsequent records use `SHA-256(previous_hash || canonical_envelope_bytes)`.
The envelope binds chain position as well as actor, event, policy and payload.
The caller locks the provisioned head, derives the successor and atomically
inserts/advances it on the business transaction's connection. Different shards
can append concurrently; the same shard's predecessor selection is serialized.
Rollback discards business change, event and head advance together.

## Signed epoch commitment

At close, the seal represents every shard id `0..N-1`, sorted numerically.
Non-empty shards commit count, dense sequence bounds and terminal hash. Empty
shards retain zero counts, null sequence bounds and a domain-separated sentinel
binding the same chain identity. Missing or duplicate shards are invalid;
omitting empty shards changes the commitment.

`EpochRootDerivation` computes:

```text
SHA-256(UTF8("meldtech.audit.epoch-root.v1\0")
        || previous_root_hash || uint64be(root_seq)
        || canonical_v1(epoch identity, version, count, ordered shard material))
```

One per-tenant root chain connects **all** retention classes and periods. The
initial predecessor is 32 zero bytes; every subsequent root references the
previous signed epoch root. The versioned signature message binds sequence and
root using a separate protocol domain. Retain signing key versions and public
verification material with the evidence. Dedicated signing authority, not the
ordinary application identity, protects these commitments from retrospective
forgery by an application-role compromise.

## Concurrent close cannot choose a fork

Close order is ascending UTC period, then `RESULT_CORRECTION_EVIDENCE`,
`RESULT_PUBLICATION_EVIDENCE`, `PIN_SECURITY_EVENT`, `GENERAL_AUDIT_EVENT`.
Read the observed tenant root head, derive the next root, sign
outside the database transaction, then insert the seal and compare-and-swap
the observed sequence/hash in one transaction. Exactly one successful head
update commits. Unique tenant/sequence and epoch constraints add backstops.
A loser rolls back its seal, re-reads, re-derives and obtains a new signature;
it cannot reuse the losing signature or overwrite a winning seal. Bounded
retries defer work rather than weakening the predicate. Scheduler ordering
alone is not the concurrency guarantee: the database CAS is authoritative.

## Why sharding parallelises the write, not the proof

Each shard's ordered links detect changed, reordered or missing records when
re-walked against its trusted commitment. The complete shard inventory and
counts prevent a verifier from accepting only convenient non-empty shards.
Signed roots bind those commitments to one ordered, dense per-tenant history,
so independent shard heads cannot become unrelated proofs. Verification must
check every expected shard, signature, predecessor and root sequence; min/max
sequence metrics alone cannot establish density. Signed checkpoints attest
open-chain prefixes; full re-walks still inspect all retained evidence. The
current daily implementation walks supplied open chains from their seeds.

After authorised disposal, retained seals/checkpoints, disposition evidence
and root links attest the boundary without pretending deleted event payloads
remain re-walkable. Never splice roots, reset seeds or repair a chain to make a
verification failure disappear. Cryptographic hashes alone do not prove that
the source facts were true, that a missing workflow emitted, or that an input
inventory is complete. Real transaction, concurrency, signature and lifecycle
tests remain necessary. See [retained acceptance evidence](evidence/FEAT-AUD-001/P7.28-acceptance-verification.md)
and the [Phase 6 deferrals](evidence/FEAT-AUD-001/P8.10-deferral-register.md).
