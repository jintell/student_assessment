# Telemetry Hygiene Guide

This guide applies to logs, span names and attributes, metric names and
labels, exemplars, exporter payloads, health details, and telemetry failure
messages. Operational telemetry is a least-data surface, never an alternate
identity, credential, answer, or audit store.

## Central Field Policy

All telemetry surfaces use
`org.meldtech.platform.shared.kernel.security.SecretFieldPattern`. Do not copy
its segment list or create a feature-local regular expression. Field paths are
normalized across camel case, acronyms, dots, underscores, and hyphens, then
matched by whole segment.

The prohibited segments are:

- `pin`
- `otp`
- `token`
- `secret`
- `password`
- `key`
- `authorization`

Examples such as `authorizationToken`, `candidate_pin`, `otp.value`, and
`private-key` are prohibited. A secret-named field is invalid even when its
value is `[REDACTED]`; omit the field. Serializer redaction is a containment
fallback for an attempted violation, not an authoring pattern.

## Permitted `key` Fields

`policy_key` is the only permitted `key` leaf. It identifies a reviewed,
non-secret policy catalogue entry and is required by retention metrics. The
allowlist is exact and closed so adding an innocent-looking `key` field cannot
silently create a credential leak path.

The exception applies only when the canonical leaf name is exactly
`policy_key`. It does not permit `policyKeyValue`, `encryption_key`,
`apiKey`, nested credential fields, or arbitrary map entries. Startup fails
unless `cbt.observability.redaction.permitted-key-fields` exactly matches the
kernel catalogue. Any addition requires Security review and a change at the
single shared-kernel definition site.

## Personal and Business Data

Email addresses, personal names, answer content, raw candidate identifiers,
request or response bodies, provider payloads, Redis keys and values, SQL
parameters, source IP addresses without an approved use case, and raw
exception messages are prohibited.

Candidate identity is omitted by default. Where a documented incident use
case requires a stable reference, use `CandidateIdentifierHasher`: HMAC-SHA256
with a secret resolved at runtime, unique per environment, and at least 256
bits. Never log the source identifier, hash secret, secret reference, or key
version. Rotation creates a new namespace and must not make identities
joinable across environments.

`actorId` and `tenantId` are approved opaque identifiers in protected logs and
spans where their contracts require them. They are never metric labels.
`actorId` must not be an email, login, or display name. `correlationId` is an
opaque diagnostic join key and carries no business meaning.

## Safe Authoring Rules

Use typed telemetry records and closed enums. Do not serialize arbitrary
objects or maps. Keep operational log messages constant and single-line. Use
stable error codes rather than exception messages. Metric labels must be
declared in the cardinality contract and span attributes must come from the
approved attribute catalogue.

Telemetry failure handling must not echo the rejected field or value. Drop
only the malformed telemetry item, increment the bounded rejection/drop
signal, and preserve the originating request's business outcome. Never log a
telemetry serialization error by serializing the same failed payload again.

Operational logs and immutable audit records use different writers, sinks,
access controls, retention, and delivery guarantees. A log event does not
discharge an audit obligation, and audit payload rules do not grant permission
to duplicate audit data into logs.

## CI Stage 10

The blocking log limb captures representative JSONL operational output and
runs `operationalLogSecretScan`. The scanner rejects:

- a missing capture directory or marker file;
- an empty marker set or an empty log capture;
- blank, malformed, or non-object JSON events;
- any nested field path matched by `SecretFieldPattern`; and
- any exact adversarial PIN, OTP, token, password, or answer sentinel value at
  any location in the line.

Failure messages identify the file, line, path, and violation kind without
echoing the leaked value. Stage 10 also joins the separately owned error,
audit, static PIN, and tenant-isolation limbs; the observability feature owns
only the operational-log/export limb.

Run the focused checks locally:

```bash
./gradlew telemetrySchemaGate operationalLogSecretScan
./gradlew test --tests 'org.meldtech.platform.shared.kernel.security.SecretFieldPatternTest'
./gradlew test --tests 'org.meldtech.platform.platform.infra.observability.OperationalLogLeakScannerTest'
```

Any discovered leak blocks release. Remove the unsafe field or value at its
origin, add an adversarial regression case, rerun the focused checks, and then
rerun CI stage 10. Do not weaken a marker, broaden the allowlist, or disable
the scanner to make a feature pass.
