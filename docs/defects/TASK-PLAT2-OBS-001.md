# TASK-PLAT2-OBS-001 Database Isolation Dashboard Gap

Status: **OPEN - RAISED AND HANDED OFF**

Primary owner: `FEAT-OBS-001`

Dashboard owner: `FEAT-OPS-004`

Architecture documentation owner: Architecture Owner

Raised by: `FEAT-PLAT-002`

## Gap

Architecture section 16.4 requires a P1 alert on any increment of
`db_context_missing_total`, but section 16.5 names no dashboard panel that
lets an operator triage the isolation backstop. The nearest home is dashboard
6, Platform Health. An alert without the role baseline, installation failures,
and reset failures leaves the responder without the evidence needed to locate
the defective path.

## Resolution Adopted by This Feature

`FEAT-PLAT-002` emits the four bounded database security-context counters and
ships exercised P1/P2 alert rules. This record hands off dashboard registration
and operational routing; it does not claim the launch dashboard exists.

## Next-Baseline Action

Add the four database security-context metrics to architecture section 16.2,
retain the P1/P2 alert rows in section 16.4, and add the panel group below to
section 16.5 dashboard 6. Name `FEAT-OBS-001` as metric-catalogue owner and
`FEAT-OPS-004` as dashboard, routing, and exercise owner.

## Proposed Platform Health Panel

Add a **Database security-context enforcement** panel group to dashboard 6.

| View | PromQL | Presentation |
|---|---|---|
| Missing-context refusals | `sum(increase(db_context_missing_total[$__rate_interval]))` | Stat fixed at zero; red on any positive value, with P1 alert annotation |
| Context-install failures | `sum by (role) (increase(db_context_install_failure_total[$__rate_interval]))` | Time series by closed role; P2 alert annotation |
| Successful role assumptions | `sum by (role) (rate(db_role_assumption_total[$__rate_interval]))` | Time series used as the bounded role-usage baseline |
| Connection reset failures | `sum(increase(db_connection_reset_failure_total[$__rate_interval]))` | Stat fixed at zero with pool-security diagnostic link |

The panel must link to `docs/runbook-database-security-context-missing.md` and
the retained `ARC-VERIFY-024` report. It must not add tenant, actor,
correlation, connection, SQL, or exception labels.

## Closure Criteria

- `FEAT-OBS-001` registers the four metric contracts in the observability
  catalogue.
- `FEAT-OPS-004` adds the panel group to dashboard 6 and routes the P1/P2
  alerts.
- A synthetic missing-context refusal and role-assumption failure appear on
  the panel and produce retained alert-delivery evidence.
- The runbook link resolves and the P1 alert cannot be muted as nuisance
  traffic.
