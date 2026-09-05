# Conformance Reference Slice

Status: normative design for `FEAT-PLAT-001` (`P2.10`). This slice is an executable architecture exemplar, not a business capability.

## HTTP Contract

```http
GET /api/v1/platform/conformance-reference
Authorization: Bearer <workforce-token>
X-Correlation-Id: <optional-valid-id>
```

The route is workforce/operator-only, returns `application/json` with `Cache-Control: no-store`, and declares operation id `platform.getConformanceReference`. It returns build and conformance metadata only:

```json
{
  "applicationVersion": "0.0.1-SNAPSHOT",
  "architectureVersion": "1.4",
  "architectureCommit": "aa7fdc5bd7b4a558e3fea19cf9c69b65ed64582b",
  "contextModuleCount": 12,
  "platformModuleCount": 4,
  "rules": ["R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8"]
}
```

No tenant data, health details, environment variables, dependency versions, credentials, filesystem paths, hostnames, or internal exceptions are returned.

## Slice Anatomy

Package: `org.meldtech.platform.platform.slice.getConformanceReference`.

| Type | Design |
|---|---|
| `Endpoint` | Binds only the GET route, context-derived request carrier, policy result, response status/headers, and handler publisher. |
| `Request` | Empty immutable record; tenant and actor are trusted context, never query parameters or body fields. |
| `Response` | Immutable record containing only the allowlisted fields above and a defensive copy of `rules`. |
| `Policy` | Requires the operator authority `platform:conformance:read`; returns `DENY` until `FEAT-IAM-003` provides the authoritative evaluator. |
| `Handler` | Read-only single transaction boundary; invokes only this slice's `Queries` port and maps its immutable result. |
| `Queries` | Extends `TenantScopedQuery` and declares `Mono<ConformanceMetadata> load(RequestTenantId tenantId)`. |
| `SliceTest` | Uses an in-memory queries port and explicit allow/deny policy doubles without a Spring context. |

`platform.infra.InMemoryConformanceMetadataQueries` implements the port from immutable build properties. It validates that a tenant carrier is present but retains no per-request state. `FEAT-PLAT-002` may replace it with a tenant-aware platform-schema adapter without changing the slice port or handler.

## Authorization and Failure

In every environment before `FEAT-IAM-003`, policy evaluation returns `DENY`; the endpoint emits the shared non-disclosing `403` problem with a correlation identifier. There is no local operator allowlist, profile override, test credential in production code, or development permit-all branch.

## R1-R8 Conformance

| Rule | Positive/reference property |
|---|---|
| R1 | No external package imports this slice. |
| R2 | It imports only platform/shared published APIs and its own module internals. |
| R3 | Its queries port contains no SQL; the in-memory adapter references no schema. |
| R4 | The handler owns one read-only transaction and calls no handler. |
| R5 | `Queries` is tenant-scoped and its method takes the tenant carrier. |
| R6 | Response/domain metadata contains no framework or infrastructure dependency and uses no ambient clock. |
| R7 | The slice performs no cross-module write or asynchronous propagation. |
| R8 | The handler is explicitly classified read-only; mutation fixtures can change that classification and prove a missing audit emission fails. |

The conformance suite mutates copies/fixtures of this slice to violate each rule; production source is restored immediately and retained reports prove each rule bites.
