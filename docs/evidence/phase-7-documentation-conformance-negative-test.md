# Phase 7 Documentation-Conformance Negative-Test Evidence

- Feature: `FEAT-PLAT-001`
- Task: `P7.23`
- Harness: `ci/test-documentation-conformance`
- Gate: `ci/verify-documentation-conformance`
- Expected result: both unqualified identifier families are rejected with a non-zero exit code and the `ARC-VERIFY-016` diagnostic.
- Retention: checksummed local report under `build/reports/documentation-conformance/self-test/`; CI artifact `documentation-conformance-self-test` retained for 90 days.

The harness first proves that `REV<n>-` citations and literal `###` convention placeholders pass. It then injects `ARCH-REVIEW-999` into temporary `requirements.md` and `SPEC-CONFLICT-999` into temporary `architecture.md`; both runs must fail. The source documents and reviewed allowlist are never modified.
