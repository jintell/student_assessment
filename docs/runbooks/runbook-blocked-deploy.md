# Blocked Deploy Runbook

Use this runbook when `DeployFreezePrecondition` refuses a release with
`SESSION_OPEN` or `SOURCE_UNKNOWN`. A refusal is a release stop, not a warning.

## Inspect the freeze decision

Retain the release-manifest checksum, environment, deployment-attempt ID,
decision time, refusal reason, and the complete `SessionWindowResult`:
`state`, `reason`, `source`, `observedAt`, and `nextBoundary`.

| Refusal | Meaning | First response |
|---|---|---|
| `SESSION_OPEN` (exit 20) | The authoritative source reports an open session. | Confirm the session and wait for an authoritative safe window. |
| `SOURCE_UNKNOWN` (exit 21) | The source is absent, unavailable, timed out, stale, malformed, unsupported, or returned no result. | Restore/query the source; do not treat unknown as no session. |

Review `deploy_freeze_refusal_total` by reason. A sustained
`SOURCE_UNKNOWN` rate means the freeze is fail-closed but blind and requires a
session-source incident.

## Confirm an open session

1. Query the authoritative scheduling/session source for the target
   environment at the recorded decision time.
2. Match the returned source and observation time to the freeze result. Reject
   stale or cross-environment evidence.
3. Identify the current session window and its `nextBoundary`. Do not infer a
   safe boundary from pod activity, traffic volume, a calendar screenshot, or
   an operator's local clock.
4. After the boundary, rerun the precondition. Continue only when a fresh
   authoritative result is `NONE` and all other release gates remain green.

The default `UnknownSessionWindowQuery` is deliberately not authoritative. If
it is active, normal deployment remains blocked until `FEAT-EXAM-001` supplies
the real adapter or the governed emergency path below is approved.

## Diagnose an unknown source

Use the detailed `SessionWindowReason`:

- `SOURCE_NOT_CONFIGURED` or `UNSUPPORTED_ENVIRONMENT`: correct adapter or
  environment configuration.
- `SOURCE_UNAVAILABLE` or `SOURCE_TIMEOUT`: restore connectivity/service and
  repeat the bounded query.
- `EMPTY_RESPONSE` or `MALFORMED_RESPONSE`: repair the source contract; an
  empty response is not an empty schedule.
- `STALE_OBSERVATION`: obtain a fresh authoritative observation.

Do not increase the timeout indefinitely, replace the adapter with a constant
`NONE`, or bypass the check through an environment variable/pipeline flag.

## Emergency override

Use an override only for a declared incident where delaying the deployment is
the greater reviewed risk. The evidence must exist in the governed evidence
store; a pipeline parameter may carry only its identifier.

The immutable evidence must contain:

- an active incident reference;
- the exact target environment, release-manifest checksum, and deployment
  attempt identifier;
- an issue time, a future expiry, an integrity-verified evidence digest, and a
  stated reason;
- one signed approval from a named `ENGINEERING_LEAD` and one from a named
  `PLATFORM_OPS` approver;
- two distinct approvers, neither of whom is the deployment requester; and
- both signatures bound to the same evidence digest.

Submit the evidence identifier with the exact request context.
`EmergencyOverridePolicy` resolves and validates the record, confirms the
incident remains active, and writes `EmergencyOverrideAuditEvent` before
permitting the attempt. Missing, expired, mismatched, unverifiable, reused,
single-approver, requester-approved, or unauditable evidence is refused.

The override applies only to that environment/release/attempt and does not
bypass release-manifest validation, stage 12, image provenance, N-1
compatibility evidence, or the `CONTRACT_ROLLBACK_FORBIDDEN` policy.

## Incident record and closure

Retain the original freeze decision, authoritative source evidence, override
record/digest, approver roles, audit event, deployment result, and post-release
verification under the incident. Never include credentials or session/candidate
data in the record.

Close only after the authoritative source is healthy, the release result is
verified, `SOURCE_UNKNOWN` has stopped increasing, and any emergency access is
expired or revoked. Reusing the evidence for another attempt is prohibited.
