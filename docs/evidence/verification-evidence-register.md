# Verification Evidence Register

| Evidence ID | Verification | Result | Retained artifact | SHA-256 | Retention |
|---|---|---|---|---|---|
| `FEAT-PLAT-001-P7.4-P7.11-CONFORMANCE-NEGATIVE` | R1-R8 deliberate violations | PASS | `phase-7-conformance-negative-tests.md` | Computed by the release bundle | Repository history plus release bundle |
| `FEAT-PLAT-001-P7.21-STAGE-4A-SELF-TEST` | `ARC-CICD-020`-`024`, eight-case self-test | PASS, 8 top-level cases and 13 assertions | `stage-4a-self-test-summary.json` | `2dbd9ffdbafc36e86cc20629c9f1df6d071abc720cee07e6cec2c07958c2741c` | Repository history; CI artifact `stage-4a-self-test-result` retained 90 days |
| `FEAT-PLAT-001-P7.21-STAGE-4A-LIVE` | Live ratification against `arch-v1.4` | PASS at step 12 | `stage-4a-live-run.txt` | `582e0054e7f6cf7e1145b6312fdc24c0efa238f49dee44b5e1d4b9ed89b7533e` | Repository history; CI artifact `stage-4a-live-ratification` retained 90 days |
| `FEAT-PLAT-001-P7.24-CLEAN-PIPELINE` | Owned blocking stages 1, 2, 3, 4a, 4, 5, 7 and 13 | PASS | `phase-7-clean-pipeline-run.md` | Computed by the release bundle | Repository history plus release bundle |
| `FEAT-PLAT-001-P7.25-ACCEPTANCE` | Five feature-card acceptance outcomes | VERIFIED | `feat-plat-001-acceptance-verification.md` | Computed by the release bundle | Repository history plus release bundle |

Release assembly must include both Stage 4a artifacts from the same green CI run. A console observation without the uploaded artifacts does not satisfy launch condition `L11`.
