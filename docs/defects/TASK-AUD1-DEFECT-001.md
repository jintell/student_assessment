# TASK-AUD1-DEFECT-001 Canonical Audit Bytes Are Undefined

Status: **RESOLVED - CANONICAL CODEC DIRECTION APPROVED**

Owner: Architecture Owner

Raised by: `FEAT-AUD-001`

## Baseline Defect

Architecture section 9.5 defines:

```text
record_hash = SHA-256(prev_hash || canonical_json(event))
```

It does not define the bytes represented by `canonical_json(event)`. Object-key
ordering, Unicode normalization, timestamp precision, number rendering, and
null-versus-absent behavior can therefore vary with libraries, JVM settings,
or later refactoring. Any variation changes the record hash and makes valid
historical evidence appear tampered with.

## Decision Requested

Approve a frozen audit-owned canonical codec with these invariants:

- the codec lives in `audit.domain` and does not consume a configurable
  application object mapper;
- object keys are deterministically sorted at every nesting level;
- keys and string values are UTF-8 encoded after NFC normalization;
- timestamps use UTC RFC 3339 with one fixed precision;
- integers and decimals have one canonical, locale-independent form;
- null and absent fields remain distinct and explicitly specified;
- every persisted row carries `hash_algo_version`, starting at version `1`;
  and
- committed golden vectors freeze the exact bytes and resulting hashes.

Task `P2.4` will specify the exact version-1 byte grammar and vectors after
this direction is approved. Its result may narrow these invariants but may not
relax them.

## Change Policy

Once a version has written a row, its codec and golden vectors are immutable.
A byte-affecting change creates a new `hash_algo_version`; it never rewrites or
re-hashes an existing audit row. Verification selects the codec recorded on
the row and must retain support for every version present in retained data.

## Approval Resolution

The Architecture Owner approved `AUDIT_CANONICAL_JSON_V1` with every invariant
and versioning rule above in:

- `ci/dor/FEAT-AUD-001/P0.5-canonical-audit-codec-approval.json`; and
- `P0.5-canonical-audit-codec-approval.architecture-owner.sig`.

`ci/verify-audit-canonical-codec-approval` verifies the complete decision and
the designated trusted signer. Task `P2.4` may now specify the exact version-1
byte grammar and golden vectors within the approved constraints. It may not
relax an invariant or rewrite an existing hash.
