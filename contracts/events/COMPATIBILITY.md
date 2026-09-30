# Event Schema Compatibility Specification

Status: normative `FEAT-PLAT-004` P2.10 design for the blocking CI stage 9
event-contract gate.

## Registered Baseline

Each event version has exactly one JSON Schema 2020-12 document named
`contracts/events/<context>.<Event>.v<n>.json`. The filename, root `$id`, and
root `properties.eventType.const` must name the same type. The committed file
is the registered baseline; CI compares it with the merge-base copy and fails
if generated schemas from event record types do not match the committed files.

Schemas use explicit `required`, closed objects, bounded strings and arrays,
and stable `x-semantic-id` values on every property. A property's type,
meaning, units, nullability, format, and semantic id form its contract.
`contracts/events/consumers.yaml` is the closed registry of consumer ids,
handled versions, and enum-default test ids. Retirement evidence lives under
`contracts/events/retirements/` and contains the telemetry interval, zero
consumption count, event owner, and approval.

## Compatibility Assertions

| Architecture section 11.3 rule | Checker assertion | Result within one version |
|---|---|---|
| Add an optional field | A new property is absent from `required`, has a new semantic id, passes P2.9, and leaves `additionalProperties: false`. | Pass |
| Add a new event type | A new correctly named schema has no merge-base predecessor, has an owner and at least one declared consumer, and does not remove an old schema. | Pass |
| Add an enum value | For every consumer registered for that version, `consumers.yaml` names a default branch and an existing proving test. Missing coverage for one consumer fails. | Conditional pass |
| Remove a field | Every merge-base property still exists at the same JSON pointer. | Fail; publish a new version |
| Rename a field | Removal plus addition is treated as removal; aliases do not make it compatible. | Fail; publish a new version |
| Narrow a type | Type-set removal, making optional fields required, disallowing null, tighter numeric/string/array bounds, format changes, or enum-value removal fails. | Fail; publish a new version |
| Change semantics | `x-semantic-id`, description, units, or declared meaning changing at an existing JSON pointer fails. Reusing the old metadata for changed behavior is contract fraud and a review failure. | Fail; publish a new version |
| Retire a version | Deletion requires telemetry evidence whose closed interval is at least 30 consecutive days and whose consumption count is zero, plus event-owner approval. | Conditional pass |

Any change not proven compatible is incompatible by default. Diagnostic output
contains event type, JSON pointer, and assertion id, not payload data.

## Version Transition

A breaking change creates `<context>.<Event>.v<n+1>.json`; it never edits the
meaning of `v<n>`. The old schema stays registered while the producer publishes
both versions and consumers migrate explicitly. The old version can be
removed only through the retirement assertion above. An unhandled version is
routed to the DLQ with `UNHANDLED_EVENT_VERSION`, so compatibility failure can
never become a silent runtime drop.
