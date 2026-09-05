# API Conventions

Status: normative design for `FEAT-PLAT-001` (`P1.6`, `P2.8`). Source: architecture §§10.1-10.3 and 10.6-10.7.

## Interaction and Resource Model

- [x] Client APIs use REST/JSON over HTTPS; AMQP is internal-only and SSE is limited to provisional-feedback readiness.
- [x] Versioned client routes start at `/api/v1`; provider callbacks remain outside `/api/v1` on management routes.
- [x] Route groups are partitioned by audience and credential: workforce, elevated PIN administration, proctor/candidate exam delivery, result access, corrections, operations, and provider callbacks.
- [x] Resources use plural nouns and are nested only when ownership is real.
- [x] State transitions are sub-resource creation, such as `POST .../publication`, `.../submission`, or `.../revocation`; clients never set arbitrary state fields.
- [x] Identifiers are UUIDv7 and timestamps are RFC 3339 UTC.

## HTTP Semantics

- [x] `GET` is safe/cacheable, `PUT` is an idempotent full replacement, `PATCH` merges explicitly named fields, and `POST` creates a resource or requests a transition.
- [x] Success responses use `200`, `201` with `Location`, `202` for accepted asynchronous work, or `204` where no representation is returned.
- [x] Failures use `400` validation, `401` unauthenticated, `403` unauthorized or non-disclosing refusal, `404` absent or tenant-invisible, `409` state conflict, `410` destroyed material, `422` invariant violation, `423` lockout, `429` rate limit, and `503` degraded with `Retry-After`.
- [x] Cross-tenant resources return `404`; PIN refusal is generic and does not reveal whether a PIN exists for another candidate.
- [x] Candidate-facing and PIN responses use `Cache-Control: no-store`; reference data may use short `max-age` plus `ETag`.
- [x] Boundary requests use bean validation, reject unknown fields, and validate tenant ownership before mutation; domain invariants remain in `domain`.
- [x] Requests and responses are `application/json`; request bodies are size-capped and `Content-Type` is enforced strictly.
- [x] Bounded configuration values expose integer `minimum`, `maximum`, and `default` metadata and are optional when omission selects the default.

## Versioning

- [x] Breaking changes require a new URI major version; additive compatible changes remain within the current major version.
- [x] Deprecation uses `Deprecation` and `Sunset` headers with at least 90 days' notice.
- [x] CI compares generated OpenAPI with the released baseline and blocks removed fields, narrowed types, new required fields, or changed enum semantics unless the major version changes.

## Pagination and Filtering

- [x] Collections use keyset pagination ordered by `(tenant_id, created_at DESC, id DESC)`, never offset pagination.
- [x] Collection responses use `{ "items": [...], "nextCursor": "...", "hasMore": true }`.
- [x] Cursors are opaque to clients and preserve the tenant-scoped ordering tuple.
- [x] Each slice defines an explicit filter allowlist; there is no generic query language.
- [x] Every allowed filter must retain tenant scoping and have an index-supported query plan.

## Normative Slice Contract

Each `Endpoint` declares one stable `operationId` in the form `<module>.<verbNoun>`, its audience/security scheme, request and response media types, all success statuses, and every allowlisted problem response. `Request` and `Response` records are the single schema source; an endpoint must not maintain a second hand-written payload model.

Public schemas expose only API DTOs. Domain types, persistence entities, answer keys, PIN/OTP/token fields, internal exception details, and unbounded generic maps are excluded. Unknown request properties are rejected. Every tenant-scoped identifier is documented as tenant-relative even when represented as UUIDv7.

All operations declare the shared RFC 9457 problem schema with mandatory `status`, stable `code`, and `correlationId`. Security declarations are audience-specific; a workforce JWT scheme is never silently reused for candidate exam tokens or unauthenticated provider callbacks.

Collection operations document their exact cursor tuple and filter allowlist. A generated operation with offset/page parameters, an undocumented filter, or a collection response outside the standard envelope fails API conformance.

## OpenAPI Generation Approach

1. Use the Spring WebFlux OpenAPI generator compatible with the repository's Spring Boot line to inspect slice endpoint/route metadata and immutable request/response records during the build.
2. For functional routes, bind route metadata explicitly (`operationId`, path, method, audience, schemas, statuses); generation must not infer a weaker contract from handler reflection alone.
3. Generate one deterministic OpenAPI 3 document at `build/generated/openapi/openapi.yaml`. Sort paths/components and strip timestamps or host-specific fields so identical source produces identical bytes.
4. Validate the generated document for unique operation IDs, complete route coverage, schema validity, explicit security, declared problem responses, `/api/v1` versioning, and absence of internal/secret-bearing fields.
5. Publish the generated document as a build artifact and use the released v1 document as the immutable comparison baseline. Generated content under `build/` is never committed or hand-edited.
6. CI stage 9 later performs the breaking-change diff: removed operations/fields, narrowed types, new required fields, or changed enum semantics block unless the URI major version changes. That gate is owned by `FEAT-OPS-*`; this feature fixes only generation and conventions.

The generated document is evidence of the slice contracts, not a substitute for them. A route that cannot be generated completely fails the build rather than being omitted from OpenAPI.
