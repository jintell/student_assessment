# Audit chain break

1. **Preserve the evidence and escalate immediately as P1** to the security
   incident lead and audit owner. Keep the original records, chain heads,
   checkpoints, seals, signatures, key-version references and verification
   findings unchanged; retain a restricted immutable incident snapshot.

Trigger: failed link/sequence/checkpoint/signature/root verification, detected
fork, root_seq gap or two seals at one sequence position. Owner: Audit with
Security, Operations and database support. A chain is **never repaired** and a
fork is **never reconciled into a preferred branch**. Rewriting hashes, deleting
a sibling seal, choosing a winning history or re-signing evidence destroys the
proof and is not an incident remedy.

## Contain and establish scope

Use the approved incident controls to halt sealing and disposition for affected
tenants/epochs; verify the halt rather than assuming an alert performed it.
Keep ordinary writes fail-closed on audit append failure. The incident lead
decides any broader traffic containment; do not disable audit or security.
If a control/identity is unavailable, escalate instead of granting ad hoc access.

Record environment, release/schema/hash versions, time range, run/correlation
reference, affected tenant/epoch/shard and the last good finding. Use protected
evidence storage; do not put raw payloads, personal data, secrets or signing
material in ordinary logs/tickets. Collect only the approved evidence scope.

Distinguish a verified mismatch from unavailable evidence, database timeout,
missing public key or unsupported historical codec. Those operational failures
also block verification acceptance; they do not by themselves prove tampering.
Check expected inventory/coverage so an empty or partial read cannot look clean.
Inspect immutable seals, root order, original canonical bytes and key version
with the approved read-only verifier; do not run corrective SQL.

## Recovery and closure

Restore availability of correct historical codecs/public keys/providers through
the approved deployment path, then re-run verification against unchanged
evidence. A confirmed integrity incident needs the incident lead's recovery
decision and an independently preserved evidence trail. Database recovery must
use the approved restore workflow and POST_RESTORE full re-walk, not manual
hash repair. Keep unresolved sealing/disposition halted.

Close only with preserved findings, root-cause/impact assessment, a full expected
inventory verification, confirmed alert routing and authorized release of the
halt. Retain failed as well as successful reports. Never reset a counter or
delete history to make the dashboard green.

## Current readiness

P9.1 registers a fork counter but its initial process zero is not a clean-chain
attestation. P9.3/P9.4 and P8.8 still lack the complete deployed detector,
preservation/P1 sink and scheduler. Follow approved incident channels directly
until automation is delivered; this runbook does not establish those controls.
