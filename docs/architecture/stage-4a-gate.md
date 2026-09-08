# Stage 4a Ratification Gate Specification

Status: normative implementation design for `FEAT-PLAT-001` (`P2.12`). Authority: architecture §18.3 (`ARC-CICD-020`-`024`). `ci/stage-4a.md` is cited as reference logic only and is non-normative where this document lists a divergence.

## Outcomes and Control Flow

The ratification limb has three reported results: `PASS`, `BLOCK`, or `FAIL`. `BLOCK` means required governance evidence is absent or not yet approved; `FAIL` means the artifact is malformed, invalid, or drifted from the ratified baseline. `BLOCK` and `FAIL` both exit `1`, stop at the first failed step, and prevent every implementation/deployment stage from running. Only `PASS` exits `0`.

The executable emits one terminal classification and one reason. It never warns and continues, aggregates later errors, or accepts an environment variable/flag that changes a blocking result.

## Twelve-Step Evaluation

| Step | Check | Failure |
|---|---|---|
| 1 | Locate `ci/architecture-ratification.json`. | `BLOCK` if absent |
| 2 | Parse JSON and validate the §21.0 record shape, required scalar types, signature references, and no unresolved template placeholders. | `FAIL` if malformed |
| 3 | Require `status == RATIFIED`. | `BLOCK` when pending/missing; `FAIL` for an unknown value |
| 4 | Require `baseline.architectureVersion == "1.4"`. | `FAIL` on mismatch |
| 5 | Require `baseline.gitTag == "arch-v1.4"`. | `FAIL` on mismatch |
| 6 | Require `baseline.documentPath == "workspace/v3/be/architecture.md"`, the actual path in the architecture Git tree. | `FAIL` on mismatch |
| 7 | In the architecture checkout, resolve `arch-v1.4` with `git rev-list -n 1` and require one 40-hex commit. | `FAIL` if absent/ambiguous |
| 8 | Constant-time compare the resolved commit with `baseline.gitCommit`. | `FAIL` on mismatch |
| 9 | Hash the raw blob bytes at `<commit>:<documentPath>` with SHA-256 and compare the 64-hex digest with `baseline.blobSha256`. | `FAIL` on mismatch |
| 10 | Require the Architecture Owner identity, `approved: true`, UTC attestation, detached signature reference, expected trusted key fingerprint, and a valid signature over the exact JSON bytes. | `BLOCK` if approval evidence is missing/false; `FAIL` if present but invalid |
| 11 | Apply the same checks to the Engineering Lead and require distinct identity/key plus `countersignedBy` matching that approver. | `BLOCK` if approval evidence is missing/false; `FAIL` if invalid or not distinct |
| 12 | Require exactly `ADR-001` through `ADR-025` with their §21.0 dispositions and required gating fields for conditional/blocked decisions. | `FAIL` if any disposition is absent, duplicated, or altered; otherwise `PASS` |

## SHA-256 Algorithm

`TASK-PLAT1-DEFECT-001` is resolved as SHA-256 over the document blob **contents**, not the Git object id:

```bash
git cat-file blob "$resolved_commit:$document_path" | sha256sum | awk '{print $1}'
```

On platforms without `sha256sum`, `shasum -a 256` is equivalent. The implementation verifies tool availability and never substitutes `git rev-parse`: SHA-1 object ids are 40 hex characters and cannot satisfy the 64-hex `blobSha256` field.

The architecture text's parent-prefixed `student_assessment/workspace/...` path cannot resolve inside the tagged repository; the signed artifact and implementation use the repository-relative Git-tree path `workspace/v3/be/architecture.md`. This remains an architecture documentation erratum, not a runtime-configurable value.

## Required Absent-Artifact Output

The output is exact, followed by exit code `1`:

```text
STAGE 4a: BLOCKED

architecture-ratification.json was not found.

Required baseline:
  Git tag:  arch-v1.4
  Document: workspace/v3/be/architecture.md

Related blocker:
  PLAN-BLOCKER-001

Resolution:
  Produce and ratify architecture-ratification.json before Phase 0 implementation.

Pipeline terminated.
```

No code path may emit `Skipping`, `Continuing`, or an advisory success for an absent/invalid record.

## Non-Normative Reference Divergences

`ci/stage-4a.md` remains useful for the intended flow and exact missing-artifact message, but its embedded shell is explicitly non-normative because it:

1. the copy retained at tag `arch-v1.4` uses `git rev-parse` (a Git object id)
   instead of SHA-256 over blob bytes; the local reference copy has been
   corrected, but remains non-normative;
2. omits the architecture-version check in step 4;
3. does not validate record shape, detached signatures, distinct identities/keys, or `countersignedBy` completely;
4. omits the complete `ADR-001`-`ADR-025` disposition check in step 12;
5. emits an abbreviated missing-artifact line rather than the exact §5 output; and
6. assumes a working directory instead of resolving repository/configured architecture-checkout paths explicitly.

The Phase 4 executable and tests implement this specification, not the embedded reference shell.
