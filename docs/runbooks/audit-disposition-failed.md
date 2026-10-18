# Audit disposition verification failed

Preserve verification evidence and escalate as P1 to Audit, Privacy/Compliance
and Operations. Any VERIFY_FAILED increment is a potential integrity event,
**not an ordinary job failure to retry blindly**. For the pre-disposition
verification gate, VERIFY_FAILED means disposition halted **before detaching**,
as designed: that safety outcome must be confirmed from durable and physical
state, not assumed from a counter or exception alone.

## Identify the exact stage

Capture the request, tenant, class/month, policy key/version, authorization
reference, original retention start/due time, hold state, signatures and failed
verification finding in restricted immutable evidence. Check the recorded
last_completed_step and actual partition attachment through approved read-only
diagnostics. Never grant operator DDL access merely to investigate.

The mandatory order is shard verification, seal/dense-root verification,
committed disposal evidence, final policy/hold recheck plus detach, then
retained-tail/root verification. The last stage can fail **after** detach;
network loss can also leave DDL outcome uncertain. A generic VERIFY_FAILED
label does not prove either of those cases was pre-detach. Preserve attachment
and lifecycle evidence before deciding how to recover.

| Observed state | Required response |
| --- | --- |
| Verification failed and leaf is still attached | Leave it attached; stop disposition and investigate chain/signature/policy inputs. Do not bypass verification or mark it disposed. |
| Leaf detached or a detach may have committed | Preserve the detached relation and anchors; stop archive/drop and further disposition. Escalate for state-aware recovery and retained-tail/root verification. |
| Attachment, progress or disposal-event evidence disagree | Treat state as uncertain; retain everything and escalate. Do not replay the whole executor or create a second disposal event. |
| Signature/evidence provider unavailable | Keep acceptance blocked and restore the approved provider; do not replace verification with a permissive stub. |

## Recovery constraints

Never repair chain hashes, remove a fork, re-sign old seals, update retained
records, reattach/drop relations ad hoc or reset the retention clock. A hold
or longer policy blocks disposal even after an old due date. A signature/root
or chain mismatch follows [chain break](audit-chain-break.md).

After an operational dependency is restored, the owning approved workflow may
restart pre-detach verification with current eligibility. After committed
detach, only a reviewed state-aware resume may finish archive policy and
retained-evidence verification. The current executor starts at step one and
does not provide a general post-detach recovery command; escalate to the
privacy/audit owner instead of inventing one.

Closure requires the immutable failure report, actual attachment disposition,
unique committed disposal evidence with policy/version, valid retained epochs
and dense root chain, active-hold review, and authorized release of the halt.
P9.1's outcome counter is an adapter callback; production transition/sampling
and P1 delivery are still prerequisites, not established by this runbook.
