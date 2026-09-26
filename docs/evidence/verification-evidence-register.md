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
| `FEAT-PLAT-005-P7.11-MIGRATION-LOCK` | §19.9 migration lock-duration report | PASS | `FEAT-PLAT-005/P7.11-migration-lock-duration-report.json` | `731087d86e8775839af45357c0801ab79cc64d60be858691a655f7ad1a480676` | Repository history plus release bundle |
| `FEAT-PLAT-005-P7.17-ROLLBACK` | Code-only rollback rehearsal against the expanded schema | PASS | `FEAT-PLAT-005/P7.17-rollback-rehearsal.json` | `116d2b6cf24d674403fa1863d2ecd5b886cedbfb47e38b843b99044a1cffeda7` | Repository history plus release bundle |
| `FEAT-PLAT-005-P7.18-BACKFILL` | Throttled interruption and resumption rehearsal | PASS | `FEAT-PLAT-005/P7.18-backfill-rehearsal.md` | `981f2d053263f26ec3c2cdd073a8c96dec7302ec9ec7d935efed438e99380443` | Repository history plus release bundle |

Release assembly must include both Stage 4a artifacts from the same green CI run. A console observation without the uploaded artifacts does not satisfy launch condition `L11`.

`TASK-PLAT5-DEFECT-002`: Architecture §19.9 requires the migration
lock-duration report and the feature rehearsals, but assigns no
`ARC-VERIFY-###` identifier to migration verification. The three
`FEAT-PLAT-005` task-owned identifiers above retain the evidence without
inventing an architecture identifier; Architecture Owner assignment remains
the documented follow-up.
