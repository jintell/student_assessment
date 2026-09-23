# Migration Volumetric Profile

The canonical CI stage 12 dataset definition is
`migration/volumetrics.yaml`; its structural contract is
`migration/volumetrics.schema.json`. The profile contains deterministic,
synthetic data only. Production extracts and personal data are prohibited.

## Workload Baseline

The committed profile derives from architecture section 15.2:

| Input | Value |
|---|---:|
| Candidates per session | 5,000 |
| Concurrent candidates platform-wide | 50,000 |
| Questions per attempt | 60 |
| Answer saves per attempt, including revisions | 80 |
| Requests per candidate | 274 |
| Session-close submission burst | 5,000 |

`defaultSeed` fixes generated values and `scale` multiplies table row counts.
The same profile, seed, and scale must produce identical row counts and bundle
checksums. Stage 12 records all three in its dataset manifest and lock report.

## Table Contract

Every table has exactly one `tables` entry:

| Field | Meaning |
|---|---|
| `name` | Lower-case, schema-qualified relation name |
| `expectedToExist` | Whether the table is present in the current migrated schema |
| `rowCount` | `0` or a positive integer multiplied by `scale` |
| `primaryKey` | Ordered key columns and deterministic UUID or sequence strategy |
| `parents` | Relations that must be generated first to preserve references |
| `generationOrder` | Stable order used by the generator |
| `columns` | Deterministic generation rule for each generated column |
| `distribution` | Reviewable description of cardinality, skew, or workload shape |

The profile currently generates the platform migration fixtures and reserves
zero-row entries for future exam-critical tables. A future table remains
`expectedToExist: false` with `rowCount: "0"` until its owning migration ships.

## Contribution Procedure

The feature that creates a table owns its profile contribution. In the same
change as the first table migration:

1. Add or update the table's row in `migration/volumetrics.yaml`. A table with
   no row fails coverage; it is never silently skipped.
2. Set `expectedToExist: true` and choose a realistic row count derived from
   the architecture workload or a documented feature-specific calculation.
3. Declare all parent relations and assign a generation order after them.
4. Add deterministic rules for required columns and preserve representative
   cardinality, null frequency, skew, and revision patterns in `distribution`.
5. Increment `profileVersion` when data volume, shape, or generation semantics
   change. Keep `schemaVersion` unchanged unless the profile contract changes.
6. Extend `migration/volumetrics.schema.json` first if a new field or generation
   strategy is needed; do not add an unvalidated ad hoc key.
7. Run the generator tests twice with the same seed and compare row counts and
   checksums. Add a changed-profile assertion for the new table.
8. Run full CI stage 12 and review the resulting lock plan and duration against
   the larger dataset.

When adding a new exam-critical table, also update
`migration/exam-critical-tables.yaml`. The volumetric row and critical-table
entry serve different controls; neither substitutes for the other.

## Local Verification

```bash
./gradlew :migration-verify:test \
  --tests 'org.meldtech.migrationverify.measure.DeterministicDatasetGeneratorTest'
./gradlew :migration-verify:generateStage12Dataset
```

Generated CSV files and manifests are written below
`build/reports/migration-stage-12/dataset/` and are not committed. Review the
manifest's profile version, seed, scale, row counts, per-table checksums, and
bundle checksum before accepting a profile change.
