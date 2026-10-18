# Audit canonical JSON: frozen version 1

`AUDIT_CANONICAL_JSON_V1`, selected by `hash_algo_version = 1`, is a byte
contract, not a configurable application serializer. The implementation is
[CanonicalJsonCodec](../src/main/java/org/meldtech/platform/audit/domain/CanonicalJsonCodec.java)
over the closed kernel `CanonicalValue` algebra. Do not use the application's
Jackson mapper or deserialize/re-serialize historical evidence through it.

## Encoding rules

1. Emit one compact JSON value as UTF-8, without BOM, whitespace or trailing
   newline. Preserve array order. Recursively normalize object keys to Unicode
   NFC; reject collisions after normalization. Sort keys by unsigned
   lexicographic UTF-8 byte order, not Java string order or database collation.
2. Normalize string values to NFC too. Reject unpaired UTF-16 surrogates.
   Escape quote and reverse solidus with reverse solidus; encode controls
   U+0000 through U+001F as lowercase `\u00xx`, never short escapes such as
   `\n`. Emit other characters directly as UTF-8; do not escape `/` or
   replace non-ASCII characters with ASCII escape sequences.
3. Integers use base ten, no leading plus or unnecessary zeros; zero is `0`.
   Decimals use plain notation, no exponent or unnecessary trailing zeros,
   but retain at least one fractional digit: decimal `1.000` becomes `1.0`;
   decimal negative zero becomes `0.0`. Binary floating-point and non-finite
   inputs have no representation in the closed algebra. Never infer the
   integer/decimal type from an untyped parser.
4. Instants use UTC with exactly six fractional digits, using Java pattern
   `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` and `Locale.ROOT`. Reject finer-than-
   microsecond precision rather than rounding. Never consult default locale
   or time zone.
5. Booleans are `true`/`false`; explicit null is `null`. An absent object
   member emits no key. The current envelope emits an absent system actor and
   indefinite retention horizon as explicit nulls; tenant is always present.
   Absence and null must not be conflated.

The [canonical envelope](../src/main/java/org/meldtech/platform/audit/domain/CanonicalAuditEnvelope.java)
binds attribution, event/entity identity, policy/retention, payload and chain
position. Its field names and value types are part of the hash contract, not
ordinary DTO refactoring opportunities. Record hashing prepends the exact
32-byte predecessor to the canonical envelope bytes before SHA-256.

## Golden vectors and verification

The authoritative [version-1 golden vectors](../config/audit/canonical-json-v1-golden-vectors.json)
contain typed-input intent, exact UTF-8 hexadecimal bytes and SHA-256 digests.
They cover recursive key ordering, NFC, fixed UTC microseconds and decimal /
explicit-null / absent-member distinctions. For example the first vector
encodes exactly `{"a":1,"b":2}` as `7b2261223a312c2262223a327d`.

`CanonicalJsonGoldenVectorTest` consumes that file and reconstructs typed
inputs. `CanonicalJsonCodecTest` and `CanonicalJsonDeterminismTest` exercise
edge cases and locale/time-zone independence. Run:

```bash
./gradlew test --tests 'org.meldtech.platform.audit.domain.CanonicalJson*'
```

## Versioning rule

**A byte-affecting codec change is a new version, never a rewrite of an
existing hash.** The same applies to envelope or hash-derivation changes.
Do not update old golden bytes/digests to make a changed implementation pass.
New vectors may extend coverage without altering existing vectors. New versions
need approved specifications, vectors and a version-dispatching verifier before
new evidence is written. Preserve old implementations, key versions and schema
knowledge for as long as their evidence remains. Unsupported versions fail
closed; never retry using the newest codec. The current implementation is v1
only, not an implemented multi-version decoder. Historical records, checkpoints
and seals are immutable even when a future version corrects a design defect.
