# Decimal Arithmetic

Status: normative arithmetic and persistence convention for scores and
percentages. `FEAT-PLAT-003` owns the primitive; `FEAT-GRD-001` owns the
scoring evaluation sequence that consumes it.

## Arithmetic

Represent every score, weight, percentage, threshold, and scoring intermediate
with `BigDecimal`. Use `Decimal.INTERMEDIATE_CONTEXT`, which is
`MathContext.DECIMAL128`, for multiplication, addition, and division. Preserve
full intermediate precision through the scoring pipeline; do not round for
display or persistence in the middle of a calculation.

Construct decimals from exact text or integer values. Do not construct a
`BigDecimal` from a binary floating-point value.

Binary `double`, `float`, `Double`, and `Float` are prohibited in grading and
scoring fields, parameters, return types, and arithmetic. CI stage 4 inspects
source and bytecode and rejects these types.

## Canonical Rounding

`Decimal.roundHalfUpToWholeNumber(BigDecimal)` is the only whole-number
rounding operation. It returns a scale-zero value using `RoundingMode.HALF_UP`,
so exact ties round away from zero.

| Input | Result |
|---:|---:|
| `0.499999` | `0` |
| `0.500000` | `1` |
| `1.500000` | `2` |
| `-0.499999` | `0` |
| `-0.500000` | `-1` |
| `-1.500000` | `-2` |

Do not add a second rounding helper to a grading module. If a presentation
needs a formatted value, keep that formatting outside the authoritative
scoring result.

## Storage Conventions

| Value | PostgreSQL type | Accepted bounds | Kernel validation |
|---|---|---|---|
| Raw score | `NUMERIC(12,4)` | `-99999999.9999` through `99999999.9999` | `Decimal.requireRawScore(...)` |
| Full-precision percentage | `NUMERIC(9,6)` | `-999.999999` through `999.999999` | `Decimal.requirePercentage(...)` |

Validate before persistence. A value whose integer magnitude exceeds the
column capacity or whose scale exceeds 4 for raw scores or 6 for percentages
is rejected. Never silently truncate, round, or clamp a value to make it fit.
The owning feature migration must use these exact column definitions.

The stored percentage is the full-precision authoritative result, not the
whole-number presentation. Apply canonical whole-number rounding only at the
explicit decision or presentation step required by the grading contract.

## Verification

When changing scoring arithmetic, run:

```bash
./gradlew test --tests 'org.meldtech.platform.shared.kernel.decimal.DecimalTest'
./gradlew conformanceTest --tests \
  'org.meldtech.platform.conformance.R6DomainPurityTests'
```

Tests must cover exact ties, negative values, both accepted storage boundaries,
excess magnitude, excess scale, binary-floating-point rejection, and rejection
of a duplicate rounding helper.
