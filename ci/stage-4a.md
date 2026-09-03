# CI Stage 4a — Architecture Ratification Gate

Reference specification for the ratification limb of CI stage 4a. Normative source: architecture §18.3
(`ARC-CICD-020…024`) and §21.0. Plan side: `PLAN-BLOCKER-001` and §14.6.

**Nothing here is a committed executable.** The shell below is reference logic for whoever implements the
pipeline stage; the pipeline owns the real implementation.

| | |
|---|---|
| Baseline | architecture **v1.4** at tag **`arch-v1.4`** |
| Document hashed | `student_assessment/workspace/v3/be/architecture.md` |
| Artifact | `architecture-ratification.json` (template: `./architecture-ratification.template.json`) |
| Outcomes | **PASS** or **BLOCK/FAIL**. There is no third outcome and no skip |

---

## 1. Normative requirements

| # | Requirement |
|---|---|
| `ARC-CICD-020` | Stage 4a SHALL require `architecture-ratification.json` on every build. |
| `ARC-CICD-021` | If the artifact is absent, Stage 4a SHALL terminate with a **blocking** failure. It SHALL NOT skip, warn or continue. |
| `ARC-CICD-022` | Stage 4a SHALL PASS only when the artifact is `RATIFIED` **and** references the exact commit that `arch-v1.4` resolves to. |
| `ARC-CICD-023` | Both the **Architecture Owner** and **Engineering Lead** approvals SHALL be present and `true`, and the approvers SHALL be distinct. |
| `ARC-CICD-024` | A failed Stage 4a SHALL prevent the implementation and deployment stages from executing. |

## 2. Why three things are pinned

| Pin | Defeats |
|---|---|
| `gitTag` | Nothing on its own — a tag is a movable ref |
| `gitCommit` | A tag moved onto different bytes |
| `blobSha256` | An edit to the document at an unchanged commit |

Tag alone is not deterministic; commit alone loses the human-readable baseline identity. All three, or the
gate is defeatable.

## 3. Evaluation order

Stops at the first failure, so the reported reason is always the first thing actually wrong.

1. Locate `architecture-ratification.json` — **BLOCK** if missing.
2. Validate JSON syntax and the §21.0 record shape — **FAIL** if malformed.
3. Require `status == RATIFIED` — **BLOCK** on `PENDING`.
4. Require `baseline.architectureVersion == "1.4"`.
5. Require `baseline.gitTag == "arch-v1.4"`.
6. Require `baseline.documentPath == "student_assessment/workspace/v3/be/architecture.md"`.
7. Resolve the tag: `git rev-list -n 1 arch-v1.4`.
8. Compare the resolved commit with `baseline.gitCommit` — **FAIL** on mismatch.
9. Recompute the document blob hash at that commit; compare with `baseline.blobSha256` — **FAIL** on mismatch.
10. Require `approvals.architectureOwner.approved == true`.
11. Require `approvals.engineeringLead.approved == true`, and a distinct `countersignedBy`.
12. Require every load-bearing §21.0 ADR disposition to be represented — **PASS** only if all steps succeeded.

## 4. Self-test matrix

A blocking gate with no test of its own is an assumption. The retained result is release evidence (§19.9).

| Case | Expected |
|---|---|
| Artifact missing | **BLOCK** |
| Invalid JSON | **FAIL** |
| `status: PENDING` | **BLOCK** |
| Wrong Git tag | **FAIL** |
| Commit mismatch (tag moved) | **FAIL** |
| Blob hash mismatch (file edited) | **FAIL** |
| Architecture Owner **or** Engineering Lead approval missing or `false` | **BLOCK** |
| Fully valid ratification | **PASS** |

**BLOCK vs FAIL** is kept because they mean different things to whoever reads the pipeline: BLOCK is a
governance state a person must resolve; FAIL is a defect in the artifact or a drifted baseline. Both stop
the build.

## 5. Required output when the artifact is absent

Forbidden — this is the failure mode `ARC-CICD-021` exists to prevent:

```text
architecture-ratification.json not found
Skipping architecture validation...
Continuing...
```

Required, exit code `1`:

```text
STAGE 4a: BLOCKED

architecture-ratification.json was not found.

Required baseline:
  Git tag:  arch-v1.4
  Document: student_assessment/workspace/v3/be/architecture.md

Related blocker:
  PLAN-BLOCKER-001

Resolution:
  Produce and ratify architecture-ratification.json before Phase 0 implementation.

Pipeline terminated.
```

## 6. Reference logic (not a committed executable)

```bash
#!/usr/bin/env bash
set -euo pipefail

FILE="architecture-ratification.json"
EXPECTED_TAG="arch-v1.4"
EXPECTED_DOC="student_assessment/workspace/v3/be/architecture.md"

echo "Stage 4a - Architecture Ratification Gate"

[ -f "$FILE" ] || { echo "BLOCKED: $FILE does not exist. PLAN-BLOCKER-001 unresolved."; exit 1; }
jq empty "$FILE" || { echo "FAIL: Invalid architecture ratification JSON."; exit 1; }

[ "$(jq -r '.status')" = "RATIFIED" ] <"$FILE" \
  || { echo "BLOCKED: status is not RATIFIED."; exit 1; }
[ "$(jq -r '.baseline.gitTag' "$FILE")" = "$EXPECTED_TAG" ] \
  || { echo "FAIL: expected tag $EXPECTED_TAG."; exit 1; }
[ "$(jq -r '.baseline.documentPath' "$FILE")" = "$EXPECTED_DOC" ] \
  || { echo "FAIL: unexpected document path."; exit 1; }

ACTUAL_COMMIT=$(git rev-list -n 1 "$EXPECTED_TAG")
[ "$(jq -r '.baseline.gitCommit' "$FILE")" = "$ACTUAL_COMMIT" ] \
  || { echo "FAIL: ratification commit does not match $EXPECTED_TAG (tag moved?)."; exit 1; }

ACTUAL_BLOB=$(git rev-parse "$EXPECTED_TAG:$EXPECTED_DOC")
[ "$(jq -r '.baseline.blobSha256' "$FILE")" = "$ACTUAL_BLOB" ] \
  || { echo "FAIL: document blob does not match the ratified baseline."; exit 1; }

ARCH=$(jq -r '.approvals.architectureOwner.approved' "$FILE")
ENG=$(jq -r '.approvals.engineeringLead.approved' "$FILE")
{ [ "$ARCH" = "true" ] && [ "$ENG" = "true" ]; } \
  || { echo "BLOCKED: ratification lacks a required approval."; exit 1; }

echo "PASS: architecture baseline $EXPECTED_TAG is ratified."
```

> **Governance rule.** No architecture baseline is implementation-authoritative merely because it exists in
> Git. The baseline becomes implementation-authoritative only when its exact revision has been explicitly
> ratified and the ratification is successfully verified by CI stage 4a.
