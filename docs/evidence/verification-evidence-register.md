# Verification Evidence Register

| Evidence ID | Verification | Result | Retained artifact | SHA-256 | Retention |
|---|---|---|---|---|---|
| `FEAT-PLAT-001-P7.4-P7.11-CONFORMANCE-NEGATIVE` | R1-R8 deliberate violations | PASS | `phase-7-conformance-negative-tests.md` | Computed by the release bundle | Repository history plus release bundle |
| `FEAT-PLAT-001-P7.21-STAGE-4A-SELF-TEST` | `ARC-CICD-020`-`024`, eight-case self-test | PASS, 8 top-level cases and 13 assertions | `stage-4a-self-test-summary.json` | `2dbd9ffdbafc36e86cc20629c9f1df6d071abc720cee07e6cec2c07958c2741c` | Repository history; CI artifact `stage-4a-self-test-result` retained 90 days |
| `FEAT-PLAT-001-P7.21-STAGE-4A-LIVE` | Live ratification against `arch-v1.4` | PASS at step 12 | `stage-4a-live-run.txt` | `582e0054e7f6cf7e1145b6312fdc24c0efa238f49dee44b5e1d4b9ed89b7533e` | Repository history; CI artifact `stage-4a-live-ratification` retained 90 days |
| `FEAT-PLAT-001-P7.24-CLEAN-PIPELINE` | Owned blocking stages 1, 2, 3, 4a, 4, 5, 7 and 13 | PASS | `phase-7-clean-pipeline-run.md` | Computed by the release bundle | Repository history plus release bundle |
| `FEAT-PLAT-001-P7.25-ACCEPTANCE` | Five feature-card acceptance outcomes | VERIFIED | `feat-plat-001-acceptance-verification.md` | Computed by the release bundle | Repository history plus release bundle |
| `FEAT-PLAT-002-P7.16-TENANT-ISOLATION-MATRIX` | `ARC-VERIFY-004` current tenant-route matrix | PASS, 1 route and 3 operation rows | `FEAT-PLAT-002/P7.16-tenant-isolation-matrix.json` | `05ca21a8b1167b3f5ef470c0940e899466ecad0f72374d1ea889a59f7b290429` | Repository history plus release bundle |
| `FEAT-PLAT-002-P7.17-POOLED-CONTEXT-ADVERSARIAL` | `ARC-VERIFY-024`, launch condition `L9` | PASS in staging, 4 tests and 0 failures | `FEAT-PLAT-002/P7.17-pooled-connection-security-context-adversarial-report.json` | `08dfe2bd646e29c269d61c19fb295664858c2275fd0485f7c328d2eaf49d6e27` | Repository history plus release bundle |
| `FEAT-PLAT-003-P7.20-FAULT-INJECTION` | `ARC-VERIFY-013`, seven-layer error-boundary injection | PASS, 8 tests and 0 failures | `FEAT-PLAT-003/P7.20-fault-injection-report.json` | `d2b0c9c28d9579ad6834b0c7a46acf4f0487fbe9c9d3d4882c3527d7e33d7442` | Repository history plus release bundle |
| `FEAT-PLAT-003-P7.20-PROBLEM-ALLOWLIST` | ProblemDetail allowlist, CI stage 10 | PASS, 3 tests and 0 failures | `FEAT-PLAT-003/P7.20-problem-detail-allowlist-result.json` | `5e94e81e6241fc8f4298e46adfc418ea91b4c541240dd44f2251e3313da5d6fa` | Repository history plus release bundle |
| `FEAT-PLAT-005-P7.11-MIGRATION-LOCK` | §19.9 migration lock-duration report | PASS | `FEAT-PLAT-005/P7.11-migration-lock-duration-report.json` | `731087d86e8775839af45357c0801ab79cc64d60be858691a655f7ad1a480676` | Repository history plus release bundle |
| `FEAT-PLAT-005-P7.17-ROLLBACK` | Code-only rollback rehearsal against the expanded schema | PASS | `FEAT-PLAT-005/P7.17-rollback-rehearsal.json` | `116d2b6cf24d674403fa1863d2ecd5b886cedbfb47e38b843b99044a1cffeda7` | Repository history plus release bundle |
| `FEAT-PLAT-005-P7.18-BACKFILL` | Throttled interruption and resumption rehearsal | PASS | `FEAT-PLAT-005/P7.18-backfill-rehearsal.md` | `981f2d053263f26ec3c2cdd073a8c96dec7302ec9ec7d935efed438e99380443` | Repository history plus release bundle |
| `FEAT-OBS-001-P7.1-PROPAGATION` | `NFR-OBS-002` HTTP-to-consumer correlation and trace propagation, CI stage 8 | PASS, 3 cases on real PostgreSQL and RabbitMQ | `FEAT-OBS-001/P7.1-propagation-test-result.json` | `8c8c1fa3019cd769a25ea5840043a933eb32671b31c928e0bdf74b251e26a846` | Repository history plus release bundle |
| `FEAT-OBS-001-P7.3-BUSINESS-EVENT-COMPLETENESS` | `NFR-OBS-001` six-event MVP metric completeness, CI stages 4 and 5 | PASS, positive contract and 2 negative cases | `FEAT-OBS-001/P7.3-business-event-completeness-result.json` | `d517d982e6748f997e89de128bf55ab15790a5a291dbd60493baa9b367405ef4` | Repository history plus release bundle |
| `FEAT-OBS-001-P7.4-LOG-LEAK-SCAN` | Operational-log secret-leak scan, CI stage 10 | PASS, BLOCKING | `FEAT-OBS-001/P7.4-log-leak-scan-result.json` | `f0cd78fc2e0204d66195b08a0a860cef721432ad183713bc906a155a2ee23864` | Repository history plus release bundle |
| `FEAT-AUD-001-P7.25-AUDIT-PAYLOAD-SCAN` | Audit-payload secret-field scan, CI 10 | PASS, BLOCKING; 1 payload, 3 self-tests, 645 integration cases | [Scan result](FEAT-AUD-001/P7.25-audit-payload-scan-result.json) | `aec1940ec5b2733e667193d99b027561c40da10a106f720d734d9a41d0a33c0f` | Repository history plus release bundle |
| `FEAT-AUD-001-P7.26-APPEND-ONLY` | Append-only grants/trigger and atomic append | PASS; 520 hardening and 11 append cases | [Retained results](FEAT-AUD-001/P7.26-retained-results.json), [test report](FEAT-AUD-001/phase-7-testing.md) | `13743309ed75914a9418bd46f86fe3a70a5f3afe0c3360e667dd4388689bafa5` (results JSON) | Repository history plus release bundle |
| `FEAT-AUD-001-P7.26-DISPOSITION` | `ARC-VERIFY-032` integration limb | PASS; 5 disposition and 1 hold cases; A7 remains open | [Retained results](FEAT-AUD-001/P7.26-retained-results.json), [protocol report](FEAT-AUD-001/phase-7-scope-disposition-testing.md) | `13743309ed75914a9418bd46f86fe3a70a5f3afe0c3360e667dd4388689bafa5` (results JSON) | Repository history plus release bundle |
| `FEAT-AUD-001-P7.26-DAILY` | Daily open-chain verification, CI 8; no `ARC-VERIFY` ID | PASS; 1 local PostgreSQL fixture case | [Retained results](FEAT-AUD-001/P7.26-retained-results.json) | `13743309ed75914a9418bd46f86fe3a70a5f3afe0c3360e667dd4388689bafa5` | Repository history plus release bundle |
| `FEAT-AUD-001-P7.26-HARNESS-SMOKE` | P7.24 reduced-scale A6/A7 instrumentation | BLOCKED; no smoke artifact exists | [Dependency record only](FEAT-AUD-001/P7.22-P7.28-execution-summary.md#p724--blocked-measured-load-harness) | Not applicable: missing artifact | Retain the smoke report when P7.24 completes |

Release assembly must include both Stage 4a artifacts from the same green CI run. A console observation without the uploaded artifacts does not satisfy launch condition `L11`.

`TASK-AUD1-DEFECT-002`: daily audit-chain verification has no assigned
`ARC-VERIFY` identifier. The task-owned daily entry records a local fixture
result, not operational scheduling. P7.26 remains incomplete until the missing
P7.24 harness smoke artifact is retained; neither A6 nor A7 is discharged.

`TASK-PLAT5-DEFECT-002`: Architecture §19.9 requires the migration
lock-duration report and the feature rehearsals, but assigns no
`ARC-VERIFY-###` identifier to migration verification. The three
`FEAT-PLAT-005` task-owned identifiers above retain the evidence without
inventing an architecture identifier; Architecture Owner assignment remains
the documented follow-up.

`TASK-PLAT3-DEFECT-002`: the plan attributes shared-kernel and context
propagation to `ARC-VERIFY-008`, but the architecture register defines that
identifier as route-policy resolution owned by identity/security features. The
FEAT-PLAT-003 entries retain owned `ARC-VERIFY-013` and stage-10 allowlist
evidence without inventing a replacement identifier for the propagation gap.

`TASK-OBS1-DEFECT-002`: the approved architecture assigns no `ARC-VERIFY-###`
identifier to `FEAT-OBS-001` despite naming propagation, business-event
completeness and log-leak verification. The three task-owned entries above
retain those results against their blocking CI stages without inventing an
architecture identifier.
