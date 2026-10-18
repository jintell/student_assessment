# Audit disposition and legal holds

Quarterly compliance input for FEAT-AUD-001 and FEAT-PRIV-001. The approved
protocol is in [P2.14–P2.15](evidence/FEAT-AUD-001/phase-2-architecture-design.md#p214-ordered-disposition-protocol);
the [privacy handover](evidence/FEAT-AUD-001/P8.4-disposition-privacy-handover.md)
records implementation and consumer boundaries. Privacy owns policy approval,
versions, eligibility, legal holds and lifecycle scheduling; audit owns chain
verification and immutable evidence. Internal application types are not a
published cross-module API.

## Retention and disposal unit

Placement is retention class, then UTC calendar month; shard is a column, not
a separate disposal unit. Write-time resolution chooses the longest applicable
approved horizon and records policy key/version. Correction/publication require
at least five years or result lifetime, whichever is longer; PIN security
events require twelve months; general events require two years. These are
policy obligations, not sufficient evidence that a particular leaf is due.
Later longer policy or reclassification evidence cannot be ignored or applied
by rewriting the original record.

A physical class/month leaf may contain **multiple tenants**. Eligibility and
hold checks must cover every affected row/tenant and applicable obligation.
A tenant-scoped request or test fixture cannot authorise partition-wide removal.

## Mandatory five-step protocol

1. Freeze the target and verify shard links, dense sequences, checkpoints,
   counts and committed heads against consistent evidence.
2. Reproduce its root; verify the unique seal, signature/key version and
   predecessor membership in the dense tenant root chain. Unsealed evidence
   cannot proceed.
3. Commit and verify `audit.AUDIT_EPOCH_DISPOSED.v1` in a current open epoch.
   Include disposed epoch, ranges, root, seal/signature reference, policy key /
   version, request and authorisation references **before** detach.
4. Recheck holds, due times and newer/longer policy immediately before the
   exact leaf's detach. Only approved retention authority may detach and
   archive/drop according to policy; ordinary request roles cannot do so.
5. Re-verify retained epoch evidence and the complete root chain across the
   disposed boundary using retained seals/checkpoints and disposition evidence.
   Only then mark completion. A concurrent seal must remain valid and visible.

Record the last successful step and original request identity. A failed
pre-detach verification prevents detach. A step-five failure is **post-detach**;
an uncertain DDL outcome must be investigated before retry. The current
`AuditDispositionExecutor` starts from step one, not a general durable resume
point. Production state-aware post-detach recovery remains an owning-workflow
dependency. Never blindly replay, emit duplicate disposition evidence, or repair
the root chain to make a retry succeed. See the
[failure runbook](runbooks/audit-disposition-failed.md).

## Hold suspension and bounded over-retention

Any active intersecting hold suspends the **whole** class/month leaf as
`HOLD_SUSPENDED`. Emit `audit.AUDIT_EPOCH_DISPOSITION_SUPPRESSED.v1` with
opaque hold/legal-basis references, policy version, original retention start /
due times and correlation; do not store legal narrative or secrets. Repeating
the same suspension must not emit duplicate evidence. Do not extract, move or
repartition held records to release the rest of their chain.

The accepted trade-off is over-retention of otherwise-expired co-resident
evidence, bounded in scope by the held class/month disposal unit rather than
the entire audit store. It is **not** a fixed maximum duration or permission to
expire a legal hold. Expose class, age, hold reference and policy version for
DPO review. Continuous suspension over 90 days requires P3 review; that is not
automatic release. Every intersecting hold must be inactive and release
authorised before emitting `audit.AUDIT_EPOCH_HOLD_RELEASED.v1`. Resume using
the stored original retention start/due times, never a reset release-time clock.
Already-expired evidence must still pass all five disposal steps.

## Quarterly review packet and readiness

Retain policy/version approvals, whole-leaf inventory, hold/release references,
original clocks, step outcomes, pre/post verification reports, disposition event
and seal/root references, DDL outcomes and unresolved incidents. Review excess
retention and failed/ambiguous operations as well as successful disposals.
The [PostgreSQL integration evidence](evidence/FEAT-AUD-001/phase-7-scope-disposition-testing.md)
covers ordered disposition, holds and concurrent sealing; it does not supply
authoritative production policy/hold providers, whole-leaf orchestration,
durable recovery or scheduling. A7 remains a Phase 6 discharge condition.
