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

Every empty shard contributes this 32-byte sentinel:

```text
SHA-256(UTF-8("FEAT-AUD-001|EMPTY_SHARD|V1"))
= 47b1714f4cbd8e976e91ea9c96957b99e2047333643d50060f98f07432e62908
```

The input contains exactly the displayed ASCII characters and no trailing
line feed. The sentinel is domain-separated from event hashes, fixed for seal
derivation version 1, and paired with a zero entry in `per_shard_counts`.
Non-empty shards contribute their actual 32-byte head hash.

All `N` shards contribute in ascending numeric `shard_id` order. Omitting an
empty shard or substituting a zero-filled buffer is invalid.

## Next-Baseline Action

Add the sentinel input, digest, count rule, and ordering rule to
`ARC-AUD-005`. A future sentinel change requires a new seal-derivation version
and may not reinterpret an existing seal.
