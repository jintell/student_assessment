# Stage 4a Ratification Runbook

Status: operational runbook for the architecture-ratification limb of CI stage
4a (`P10.5`). Normative authority: architecture section 18.3 and
`ARC-CICD-020` through `ARC-CICD-024`. The executable is `ci/stage-4a`.

## Outcome Meaning

| Outcome | Meaning | Pipeline effect | Primary resolver |
|---|---|---|---|
| `PASS` | The exact architecture version, tag, commit, SHA-256 content digest, ADR dispositions, and two trusted approvals are valid. | Exit 0; downstream implementation stages may run. | None |
| `BLOCKED` | Required governance evidence is absent, pending, false, expired, or only a temporary preparatory gate exists. | Exit 1; all downstream implementation and deployment stages stop. | Architecture Owner and Engineering Lead; repository maintainer assists with artifact placement only |
| `FAIL` | Evidence exists but is malformed, untrusted, internally inconsistent, or no longer matches the pinned baseline. | Exit 1; all downstream implementation and deployment stages stop. | Build/repository maintainer diagnoses; Architecture Owner and Engineering Lead re-ratify whenever approved content or evidence must change |

`BLOCKED` is not a skipped check and `FAIL` is not a warning. Neither outcome
may be downgraded, allowlisted, retried as success, or bypassed with an input or
environment variable. The gate stops at the first failed step; resolve that
reason and run it again to expose any later issue.

## Run Locally

The default checkout expects the architecture repository at
`../student_assessment`. Override that location only to identify the checkout
being verified:

```bash
ARCHITECTURE_REPOSITORY=/absolute/path/to/student_assessment ci/stage-4a
```

Run the complete Gradle entry point, including the retained self-test and bypass
test:

```bash
./gradlew ciStage4a
```

Useful focused diagnostics are:

```bash
./gradlew stage4aUnitTest
./gradlew stage4aSelfTest
./gradlew stage4aBypassTest
```

Do not edit generated reports under `build/`. The repository-retained evidence
register identifies the reviewed summaries that are committed intentionally.

## First-Failure Triage

| Step | Reported condition | Classification | Resolution |
|---|---|---|---|
| 1 | Ratification artifact absent | `BLOCKED` | Restore `ci/architecture-ratification.json` from an approved ratification process. The maintainer cannot invent or self-approve it. |
| 2 | JSON shape invalid or template placeholders remain | `FAIL` | Correct the artifact construction, then obtain new signatures because signatures cover the exact JSON bytes. |
| 3 | Status missing/`PENDING`, temporary gate active/expired, or approval not final | `BLOCKED` | Architecture Owner and Engineering Lead complete ratification. A temporary gate permits preparatory work only and never permits implementation. |
| 3 | Unknown status or malformed temporary gate | `FAIL` | Restore the allowed schema and re-sign the changed artifact. |
| 4-6 | Architecture version, tag, or document path differs | `FAIL` | Determine whether the artifact is wrong or a new baseline was approved. Correct metadata only through re-ratification. |
| 7 | Architecture repository/tag unavailable | `FAIL` | Fetch/provide the approved architecture checkout and tag; verify repository provenance before retrying. |
| 8-9 | Commit or document SHA-256 differs | `FAIL` | Treat as baseline drift or a moved tag. Do not update the digest in place; investigate, create an immutable approved baseline, and re-ratify. |
| 10-11 | Approval missing or false | `BLOCKED` | The named Architecture Owner or Engineering Lead supplies approval; both approvers must be distinct. |
| 10-11 | Identity/key/signature malformed, absent, invalid, untrusted, or not distinct | `FAIL` | Verify trusted identity records and detached signature files. Key rotation follows the repository security process; changed evidence requires both signatures again. |
| 12 | Trusted ADR disposition reference missing, malformed, or different | `FAIL` | Reconcile all `ADR-001` through `ADR-025` dispositions with the approved baseline and re-ratify. |

Never fix a mismatch by moving an existing tag, weakening signature checks,
changing trusted values to match unreviewed content, or using `git rev-parse` as
the document SHA-256.

## Re-Ratify a Baseline Change

Re-ratification is required whenever the approved architecture document,
version/tag, ADR dispositions, approver evidence, or trusted baseline identity
changes.

1. The Architecture Owner completes architecture review and publishes a new,
   immutable version tag in the architecture repository. Do not move or reuse
   `arch-v1.4` for changed bytes.
2. Update the gate's expected version, tag, and document path through a reviewed
   code change when the approved baseline identifier changes. Update the plan
   and architecture references that name the old baseline in the same governed
   change.
3. Resolve the tag to its full commit:

   ```bash
   git -C /path/to/student_assessment rev-list -n 1 <new-tag>
   ```

4. Compute SHA-256 over the raw document blob contents at that commit:

   ```bash
   git -C /path/to/student_assessment \
     cat-file blob <commit>:workspace/v3/be/architecture.md \
     | sha256sum
   ```

   On macOS, use `shasum -a 256`. A 40-hex Git object ID is not this digest.
5. Build a final `ci/architecture-ratification.json` from the template with the
   new immutable pins, all required ADR dispositions, both approver identities
   and timestamps, detached-signature filenames, and the Engineering Lead
   `countersignedBy` value. Remove template comments/placeholders and omit the
   temporary-gate block for a ratified baseline.
6. Update `ci/architecture-ratification.expected-decisions.json` to the approved
   decision set. Confirm it contains the complete closed enumeration required by
   the updated gate.
7. Have the distinct Architecture Owner and Engineering Lead sign the exact
   finalized JSON bytes with their trusted keys. Store the detached signatures
   at the filenames recorded in the artifact. Any later byte change invalidates
   both signatures and requires signing again.
8. Run `./gradlew stage4aUnitTest stage4aSelfTest stage4aBypassTest`, then run the
   live `ci/stage-4a` against the architecture checkout. Review the first-failure
   reason rather than editing around it.
9. Commit the ratification artifact, detached signatures, expected decision
   reference, reviewed gate changes, and retained evidence together. Require the
   normal peer/security review before merging.

## Escalation

Escalate unexplained commit/blob drift, a moved approved tag, signature failure
for a supposedly unchanged artifact, or an unplanned trusted-key change as a
supply-chain/security incident. Stop implementation until provenance is
established. Governance disagreement goes to the Architecture Owner and
Engineering Lead; build mechanics go to the repository maintainer, but no
maintainer action substitutes for either required approval.

Implementation details and the exact twelve-step contract are documented in
[`architecture/stage-4a-gate.md`](architecture/stage-4a-gate.md).
