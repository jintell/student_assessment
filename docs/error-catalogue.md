# Error Catalogue

Status: normative client contract for platform error responses. The declarative
source is `src/main/resources/error-catalogue.yaml`; the table below is its
published catalogue version 1.

## Contract

Every error response uses the RFC 9457 problem-detail shape and contains
`type`, `title`, `status`, `code`, `detail`, `instance`, and `correlationId`.
Only extensions declared for the selected code may be present. The response
HTTP status equals `status`, and `correlationId` is a canonical uppercase ULID
that also appears in the `X-Correlation-Id` response header.

The stable code namespace is `CBT-PLAT-*`. Type identifiers are absolute URIs
below `https://errors.meld-tech.com/problems/`. They identify problem classes;
clients must not treat them as request-specific resources.

`code`, `type`, `title`, `status`, and `detail` are catalogue values. In
particular, `detail` is never derived from an exception message. An unmapped
exception or mapping failure returns `CBT-PLAT-INTERNAL`, so stack traces, SQL,
provider messages, and secrets cannot become response content.

## Stability

Existing codes, type URIs, meanings, status values, required fields, and
extension semantics are stable within `/api/v1`. Adding a new code or an
optional extension is compatible. Removing or renaming a code, changing its
meaning or status, removing a field, narrowing a field, or making an optional
field required is a breaking contract change and requires a new API major
version under the platform API versioning policy.

Clients should branch on `code`, tolerate catalogue additions, and display or
log `correlationId` when support needs to trace a failure. Clients must not
parse `detail` to make decisions.

## Published Codes

| Code | Status | Type | Title | Detail | Extensions |
|---|---:|---|---|---|---|
| `CBT-PLAT-CONFLICT` | 409 | `https://errors.meld-tech.com/problems/conflict` | Conflict | The request conflicts with the current state. | None |
| `CBT-PLAT-FORBIDDEN` | 403 | `https://errors.meld-tech.com/problems/forbidden` | Forbidden | The requested operation is not permitted. | None |
| `CBT-PLAT-IDEMPOTENCY-UNAVAILABLE` | 409 | `https://errors.meld-tech.com/problems/idempotency-unavailable` | Duplicate request protection unavailable | The request cannot be safely repeated at this time. | None |
| `CBT-PLAT-INTERNAL` | 500 | `https://errors.meld-tech.com/problems/internal` | Unexpected error | The request could not be completed. | None |
| `CBT-PLAT-NOT-FOUND` | 404 | `https://errors.meld-tech.com/problems/not-found` | Not found | The requested resource was not found. | None |
| `CBT-PLAT-RATE-LIMITED` | 429 | `https://errors.meld-tech.com/problems/rate-limited` | Too many requests | Too many requests were received. Try again later. | Required integer `retryAfterSeconds`, 1-86400 |
| `CBT-PLAT-UNAUTHORISED` | 401 | `https://errors.meld-tech.com/problems/unauthorised` | Unauthorised | Authentication is required to perform this operation. | None |
| `CBT-PLAT-VALIDATION` | 400 | `https://errors.meld-tech.com/problems/validation` | Validation failed | One or more request values are invalid. | None |

## Generated Contracts

Run `./gradlew generateErrorCatalogue` after changing the declarative source.
The task validates the catalogue and generates:

- `build/generated/openapi/openapi.yaml`, containing the closed
  `ProblemDetail` union, one schema and example per code;
- `build/generated/docs/error-catalogue.md`, the generated client table; and
- `build/generated/resources/errorCatalogue/error-catalogue.json`, the runtime
  catalogue.

Generated files under `build/` are evidence, not source-controlled inputs. A
catalogue change is complete only when the generated OpenAPI component and the
published table agree and the problem-detail allowlist tests pass.
