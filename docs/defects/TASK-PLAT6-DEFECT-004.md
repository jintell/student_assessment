# TASK-PLAT6-DEFECT-004: Pindist probes versus sole-route restriction

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner.

## Baseline Gap

Architecture §17.1 health availability and §17.4 every-other-path-404 wording conflict unless the listener boundary is explicit.

## Adopted Resolution

Bind probes to a separate management port excluded from business ingress. application.yaml currently uses 9091; the reserved business route remains versioned and isolated. Live network evidence is still outstanding.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/phase-6-security-hardening.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending Architecture Owner acknowledgement; no owner receipt for this revision record is retained.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Clarify that the sole-route restriction applies to the business listener and excludes isolated management probes.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
