# TASK-OBS1-DEFECT-004 Incomplete Telemetry Redaction Scope

Status: **OPEN - RAISED FOR NEXT BASELINE; LOCAL CONTROL IMPLEMENTED**

Owner: Architecture Owner

Raised by: `FEAT-OBS-001`

## Baseline Defect

`ARC-OBS-002` specifies a build-failing secret-field control for logging, but
architecture sections 16.2 and 16.3 impose the same hygiene obligation on
metric labels and span attributes without assigning an equivalent check.
Implementing only the logging limb leaves vendor telemetry surfaces able to
accept a PIN, OTP, credential, answer, or personal-data field.

## Resolution Adopted by This Feature

The single shared-kernel `SecretFieldPattern` and telemetry field policy now
govern structured logs, span attributes, metric labels, and business-event
boundaries. `TelemetrySchemaGate` rejects unsafe or unbounded schema types;
`SpanAttributeRedactor` rejects prohibited attributes; the metric
cardinality contract admits only reviewed labels and globally forbids tenant
and correlation labels. Negative tests cover a secret-named log field, span
attribute, and metric label.

## Next-Baseline Action

Extend `ARC-OBS-002` to every telemetry surface and name the stage 4 schema
gate plus stage 10 leak scan as blocking verification. Retain the single
kernel definition and the exact permitted-`key` exception; do not create
surface-specific secret patterns.

## Evidence

- `TelemetrySchemaGate` and `TelemetrySchemaGateTest`
- `SpanAttributeRedactor` and `SpanAttributeRedactorTest`
- `MetricCardinalityGate` and `MetricCardinalityGuardTest`
- `TelemetryBoundaryConformanceTests`

## Closure Criteria

- The next architecture baseline applies the redaction contract to logs,
  spans, metrics, and event telemetry.
- The blocking gate and its negative proofs are named.
- No alternate secret-field definition is introduced.
