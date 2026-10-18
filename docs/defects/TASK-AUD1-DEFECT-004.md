# TASK-AUD1-DEFECT-004 Empty Audit Shards Have No Root Contribution

Status: **OPEN FOR NEXT BASELINE - SENTINEL SETTLED LOCALLY**

Owner: Architecture Owner

Raised by: `FEAT-AUD-001`

## Baseline Defect

`ARC-AUD-005` derives an epoch root from all configured shard heads but does
not define the contribution of a shard containing zero records. Sparse epochs
are normal, so two conforming implementations could otherwise derive
different roots for the same evidence.

## Resolution Adopted by This Feature

The earlier P0.7 proposal used a constant sentinel. That proposal is superseded
by P2.7 and the implemented `EpochRootDerivation`: every empty shard contributes
an identity-bound 32-byte sentinel:

```text
SHA-256(UTF8("meldtech.audit.empty-shard.v1\0")
        || canonicalJsonV1(tenant_id, retention_class, period, shard_id, shard_count))
```

The domain label ends in one NUL byte; the canonical identity uses the frozen
version-1 grammar and has no trailing newline. There is no universal digest:
the sentinel binds the tenant, epoch and shard topology. It is paired with a
zero entry in `per_shard_counts` and null sequence bounds. Non-empty shards
contribute their actual 32-byte head hash. This corrects a stale defect record,
not the codec, existing golden vectors or any historical seal.

All `N` shards contribute in ascending numeric `shard_id` order. Omitting an
empty shard or substituting a zero-filled buffer is invalid.

## Next-Baseline Action

Add the identity-bound sentinel derivation, count rule, and ordering rule to
`ARC-AUD-005`. A future sentinel change requires a new seal-derivation version
and may not reinterpret an existing seal.

P10.10 repository escalation, 2026-10-18: Architecture Owner to incorporate
the [published chain design](../audit-chain-design.md) and settle the next
baseline. No new owner acknowledgement or historical rewrite is asserted.
