# Runbook: Rising Unmapped Problem-Detail Rate

Use this runbook for `SustainedUnmappedProblemDetails` or any unexpected rise
in `problem_detail_unmapped_total`.

## Meaning and first action

The mapper encountered an exception without an intentional catalogue mapping,
or a mapping named a missing catalogue code. The client received the fixed
`CBT-PLAT-INTERNAL` response by design. Its generic text prevents stack traces,
SQL, provider diagnostics, exception messages, and secrets from crossing the
API boundary.

First action: **identify the unmapped exception and add a catalogue entry**
when a stable public distinction is intentional. Do not expose the source
exception message and do not mute the metric.

## Triage

1. Open the unmapped-rate panel and record the start time, affected
   environment, bounded `reason`, and deployment version. Do not paste client
   bodies or protected log content into the incident channel.
2. Select a recent `problem_detail_unmapped_total` exemplar and follow its
   `trace_id` / `span_id` to the trace. Read the trace's `correlationId`.
3. Query the protected log store for that exact correlation identifier and
   time window. Confirm the response code was `CBT-PLAT-INTERNAL`, then locate
   the first application exception and its owning module/slice.
4. Compare multiple samples. Determine whether one new exception class or
   failure path dominates, whether the rise began with a deployment, and
   whether dependency, database, or resource signals explain it.
5. Escalate immediately if logs suggest cross-tenant access, authorization
   bypass, secret exposure, data corruption, or repeated candidate-path
   failure. Preserve traces and logs under the incident evidence policy.

## Decide the correction

- Fix the calling path when the exception is an internal defect clients
  cannot act on. The generic response remains correct; no new public code is
  required merely to make the counter quiet.
- Add a catalogue entry when clients need a stable, actionable distinction.
  Choose the existing `CBT-PLAT-*` namespace and approved type URI base. Use a
  fixed title, status, and detail that reveal no implementation information.
- Add only allowlisted primitive extensions. Never add a field matching
  `SecretFieldPattern`, and never construct detail from `Throwable.getMessage()`.
- Treat reuse or semantic change of a published code as breaking. Add a new
  code when semantics differ.

## Verify and release

1. Add the exception-to-code mapping at `KernelErrorConfiguration` and the
   complete declarative entry in `src/main/resources/error-catalogue.yaml`.
2. Add mapper, HTTP-boundary, allowlist, and leak assertions for the new path.
   Confirm the generated OpenAPI and client catalogue change from the same
   source artifact.
3. Run:

   ```bash
   ./gradlew test problemDetailAllowlistTest conformanceTest
   ```

4. Review the API compatibility result. Follow section 10.6 treatment if the
   change modifies an existing published contract.
5. Deploy through normal gates. Confirm the unmapped rate returns to zero and
   the expected `problem_detail_emitted_total{code=...}` rate appears instead.

## Closure evidence

Retain the alert interval, representative trace and correlation identifiers,
root cause, mapping-or-code-fix decision, catalogue diff, blocking test
results, deployment version, and post-deployment counter evidence. Reference
`TASK-PLAT3-OBS-001` until its alert and panel closure criteria are complete.
