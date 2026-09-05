# Stage 4a Self-Test Specification

Status: normative design for `FEAT-PLAT-001` (`P2.13`). Sources: architecture §§18.3 and 19.9; gate contract: `stage-4a-gate.md`.

## Fixture Model

The harness creates a fresh temporary Git repository and isolated `GNUPGHOME` for every run. It commits a deterministic architecture document at `workspace/v3/be/architecture.md`, creates tag `arch-v1.4`, generates two ephemeral test-only signing keys with distinct fingerprints, produces a complete `ADR-001`-`ADR-025` ratification record, and signs the exact JSON bytes. No private key, generated signature, temporary repository, or mutated ratification file is committed.

The valid fixture is rebuilt before each case. A case changes only the field/file named below, runs the actual Stage 4a executable, captures stdout/stderr and exit status, and removes the temporary workspace after evidence is copied. Tests assert the first terminal reason and assert that messages for later steps are absent.

## Eight Cases

| Case | Fixture mutation | Expected result | First stopping step/assertion |
|---|---|---|---|
| 1. Artifact missing | Delete `ci/architecture-ratification.json`. | `BLOCK`, exit `1`, exact absent-artifact output | Step 1; no `Skipping` or `Continuing` text |
| 2. Invalid JSON | Truncate the record after the `baseline` key. | `FAIL`, exit `1` | Step 2 JSON/shape error; no status message |
| 3. Pending status | Set `status` to `PENDING`. | `BLOCK`, exit `1` | Step 3 governance state |
| 4. Wrong Git tag | Set `baseline.gitTag` to `arch-v1.3`. | `FAIL`, exit `1` | Step 5 expected-tag mismatch |
| 5. Commit mismatch | Keep the valid tag but set `baseline.gitCommit` to another 40-hex commit in the fixture repository. | `FAIL`, exit `1` | Step 8 moved-tag/commit mismatch |
| 6. Blob hash mismatch | Replace one nibble of the valid 64-hex `blobSha256`. | `FAIL`, exit `1` | Step 9 content-hash mismatch |
| 7. Approval invalid | Parameterized subfixtures: owner missing, owner `false`, lead missing, lead `false`, equal identities/fingerprints, and mismatched `countersignedBy`. | Missing/false: `BLOCK`; present-but-not-distinct/invalid: `FAIL`; all exit `1` | Steps 10-11; each subfixture names the failed role/control |
| 8. Fully valid | No mutation; both generated signatures verify and all 25 dispositions match. | `PASS`, exit `0` | Step 12 is reached; exactly one PASS message |

Mutations before steps 10-11 need not be re-signed because evaluation must stop before signature validation. The valid fixture and any case intended to reach approval validation are signed after their final mutation.

## Retained Evidence

The build writes generated results beneath `build/reports/stage-4a/self-test/<run-id>/`:

- `summary.json`: gate digest, source commit, architecture fixture digest, runner/tool versions, start/end UTC, and each case's expected/actual class, exit code, first reason, and pass/fail;
- `junit.xml`: one test per case and one per case-7 parameter;
- `case-<n>.log`: bounded stdout/stderr with no key material;
- `checksums.sha256`: digest of every retained evidence file.

CI stage 4a uploads that directory as the named **Stage 4a self-test result** artifact. Release evidence records the CI run URL/id, source commit, artifact digest, retention policy, and the matching successful live-ratification run. The artifact is retained per release rather than merely observed in console logs, satisfying architecture §19.9 and launch condition L11.

The job fails if fewer than eight top-level cases execute, any case-7 parameter is omitted, a later-step diagnostic appears after the first failure, evidence is incomplete, or the fully valid fixture is anything other than the sole PASS.
