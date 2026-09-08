# TASK-PLAT1-DEFECT-001: Ratification Blob Hash Algorithm

- Status: Open; raised for correction in the next architecture baseline
- Raised: 2026-09-08
- Owner: Architecture Owner
- Reporter: `FEAT-PLAT-001`
- Affected baseline: architecture v1.4 at `arch-v1.4`
- Severity: High, because literal implementation makes stage 4a unsatisfiable

## Defect

Architecture section 18.3 step 9 and the ratification model require
`blobSha256` to contain the SHA-256 digest of the architecture document's raw
blob contents. The reference `ci/stage-4a.md` stored at tag `arch-v1.4` instead
uses:

```bash
git rev-parse "$EXPECTED_TAG:$EXPECTED_DOC"
```

That command returns the repository's Git object ID. In this baseline it is a
40-hex SHA-1 identifier, not a 64-hex SHA-256 content digest. A conforming
`blobSha256` value therefore cannot equal the reference command's output.

## Impact

- Implementing the tagged reference shell literally makes a valid ratification
  fail step 9.
- Changing `blobSha256` to the 40-hex object ID would violate the normative
  field meaning and weaken the independent content-integrity check.
- Teams may produce incompatible ratification records unless the next baseline
  names the algorithm and byte input explicitly.

## Adopted Resolution

`FEAT-PLAT-001` treats the architecture's SHA-256 requirement as normative and
hashes the raw document blob bytes at the already resolved commit:

```bash
git -C "$architecture_repository" \
  cat-file blob "$resolved_commit:$document_path" \
  | sha256sum | awk '{print $1}'
```

On systems without `sha256sum`, `shasum -a 256` is equivalent. The executable
`ci/stage-4a` requires a 64-lowercase-hex `blobSha256` and compares it with this
digest. It does not substitute the Git object ID. The document path is the
repository-relative `workspace/v3/be/architecture.md`.

## Requested Baseline Correction

The Architecture Owner should update the next architecture baseline and its
reference gate so they:

1. define `blobSha256` as SHA-256 over the raw bytes returned by
   `git cat-file blob <commit>:<documentPath>`;
2. show `sha256sum` or `shasum -a 256`, never `git rev-parse`, for that field;
3. distinguish the 40-hex `gitCommit`/Git object identity from the 64-hex
   independent document digest; and
4. use the repository-relative document path consistently.

## Verification Evidence

- `ci/stage-4a` implements the adopted algorithm and validates digest shape.
- `ci/test-stage-4a-unit` covers digest matching and mismatch behavior.
- `ci/stage-4a-self-test` retains the blob-hash-mismatch negative case.
- `docs/evidence/stage-4a-live-run.txt` records a live pass against
  `arch-v1.4` using the adopted resolution.

Closure requires the Architecture Owner to publish and ratify a corrected
baseline. This repository record remains open until that external correction is
available; the local gate must not regress while the record is open.
