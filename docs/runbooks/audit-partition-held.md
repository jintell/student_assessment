# Audit partition held beyond 90 days

Trigger: a HOLD_SUSPENDED partition older than 90 days. Severity P3. Owner:
Compliance/DPO with the legal-hold authority and Privacy/Audit Operations.
Bounded over-retention under legal hold is an **accepted trade-off**. The review
question is **whether the hold is still required**, not how to delete the
partition or clear the alert. Ninety days is a review threshold, not expiry.

## Review without weakening the hold

Confirm suspension start/age from the authoritative lifecycle record rather
than a process counter. Record class/month, covered tenant scope, opaque hold
references, legal-basis reference, policy version and original retention
start/due time in restricted evidence. Check every active hold intersecting
the physical leaf; one matching hold protects the whole class/month partition,
which may include several tenants. Do not split out held rows or remap them.

Ask the authorized Compliance/legal owner to confirm each hold's ongoing basis,
scope and next review date. Keep legal narrative, personal data and protected
documents out of audit payloads and operational logs. A continued hold remains
HOLD_SUSPENDED; retain its review decision, capacity impact and next review.
Monitoring/capacity pressure never authorizes release.

## Authorized release and resumption

Release only when all applicable holds are inactive under the authoritative
approval process. Use the reviewed audited hold-release workflow; never clear
state directly in SQL or act on an expired ticket alone. Preserve both the
suppressed-disposition and release audit evidence.

Resume eligibility using the **original retention start and due time**. The
release date does not restart, shorten or extend the clock. An already-expired
partition still requires the complete verify/seal/evidence/recheck/detach/
retained-verification protocol, including any newer or longer policy. Removal
of one hold while another remains cannot authorize disposition.

Close the review with its authorized decision and evidence, confirmed active
hold set, unchanged original clock and next action. If disposition fails,
follow [disposition failure](audit-disposition-failed.md); suspected evidence
tampering follows [chain break](audit-chain-break.md).

The live policy/hold authority, scheduler, age sampler and P3 route remain
owning-feature prerequisites. Local hold tests establish lifecycle invariants;
they do not establish those deployed services. If an approved release path is
unavailable, retain the hold and escalate to its owner.
