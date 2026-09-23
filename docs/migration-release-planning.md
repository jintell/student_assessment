# Planning Schema Changes Across Releases

`ADR-019` accepts slower schema evolution so zero-downtime deployments and
code-only rollback remain safe. Its consequence, recorded as trade-off `T-11`,
is binding: schema work spans multiple releases and must be planned before the
feature code that depends on it.

## Plan Backward from the Contract Release

Treat one logical schema change as a release sequence, not one ticket:

| Release | Planned work | Exit evidence |
|---|---|---|
| N, `EXPAND` | Add compatible structures; deploy dual-capable foundations; start the worker-role backfill | Stage 12 green, N-1 compatibility green, migration report retained, backfill observable and resumable |
| N+1, `MIGRATE` | Write old and new representations; read the new representation; finish and verify backfill | Dual-write observation complete, no eligible backfill rows remain, rollback image still works against the expanded schema |
| N+2, `CONTRACT` | Remove the obsolete representation in a separately classified release | Old readers/writers retired, observation window complete, forward-fix plan approved |

Do not commit a feature date that needs the new representation before its
`EXPAND` release has shipped. Do not commit the `CONTRACT` release until the
N+1 observation and rollback-retirement conditions are measurable.

## Required Planning Inputs

The feature owner records these items during refinement:

1. The old and new representations and the release where each is read/written.
2. The module-owned migration files for each phase.
3. The deterministic volumetric profile row and any exam-critical registry
   addition.
4. Backfill batch size, throttle, pause conditions, completion query, and
   estimated duration at the approved rate.
5. Dual-write behavior, read switch, and mixed-version compatibility tests.
6. The earliest safe contraction condition and observation period.
7. A forward-fix action for migration failure; schema rollback is excluded.
8. Release-manifest classifications and the retained N-1 image digest for each
   release.

Create separate deliverables for the three phases even when one team owns all
of them. Link them so the contraction cannot be scheduled independently of the
expansion and migration evidence.

## Scheduling Constraints

- The migration Job completes before rollout and deployment is refused while a
  session is open or session state is unknown.
- Backfill runs outside the migration Job and does not block a release, so its
  duration must fit between N and the N+1 read switch.
- N-1 compatibility evidence is required before every code rollout and
  rollback rehearsal.
- A `CONTRACT` release is forward-only. Reserve time for review and a forward
  fix; do not schedule it as cleanup inside another feature release.
- A new allowlist shape or threshold change needs architecture and operational
  approval before implementation, not during release hardening.

These constraints are not schedule contingency. They are the controls that
preserve `NFR-AVAIL-001`, `NFR-MAINT-002`, `ARC-OPS-006`, and
`ARC-OPS-013`.

## Example Milestones

For a feature that replaces a text answer payload with structured JSON:

- Milestone N ships nullable `response_json`, stage-12 evidence, and the
  resumable conversion backfill.
- Milestone N+1 begins dual writes, reads `response_json`, and retains the old
  `response` column for rollback.
- Milestone N+2 is scheduled only after the backfill is complete, N+1 is fully
  rolled out and observed, and the old image is retired; it drops `response`
  as a dedicated `CONTRACT` release.

A plan containing only the N+1 feature implementation is incomplete. Add the
N expansion and N+2 contraction work, owners, evidence, and release windows
before accepting it for delivery.
