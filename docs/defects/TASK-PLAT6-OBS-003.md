# TASK-PLAT6-OBS-003: Pindist pool omitted from deployment row

Recorded: 2026-10-16. Runtime task: P10.8.
Recipients: Architecture Owner.

## Baseline Gap

The five-connection pindist pool is funded in §15.2 but not stated in §17.1.

## Adopted Resolution

Consume 5 per replica from §15.2 line 2654; docs/runtime-roles.md cites it separately from the §17.1 two-replica deployment row. No additional capacity is allocated.

Evidence: [runtime implementation record](../evidence/FEAT-PLAT-006/P8.5-connection-envelope.md).
Related contract: [runtime roles](../runtime-roles.md).

## Acknowledgement

Pending Architecture Owner acknowledgement.

This is a repository handoff record. No new external delivery or owner response
is represented. Missing acknowledgements keep P10.8 open; implementation
completion is distinct from acceptance into the next baseline.

## Next Revision

Add the five-connection pool to §17.1 without changing the envelope.

Retain an attributable acknowledgement per listed recipient (identity, date,
decision and durable reference). Do not mark pending recipients accepted merely
because the implementation exists.
