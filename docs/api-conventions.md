# API Conventions

Status: normative for every HTTP slice implemented after `FEAT-PLAT-001`
(`P10.4`). Sources: architecture sections 10.1-10.3 and 10.6-10.7. A feature
may deviate only through an approved architecture decision.

## Resources and Routes

- Serve client APIs as REST/JSON over HTTPS. AMQP is internal-only; SSE is
  reserved for provisional-feedback readiness.
- Start versioned client routes with `/api/v1`. A breaking contract change
  requires a new URI major version.
- Partition route groups by audience and credential: workforce, elevated PIN
  administration, proctor/candidate delivery, result access, corrections,
  operations, and provider callbacks.
- Use plural resource nouns. Nest a resource only when its ownership is real.
- Represent a state transition by creating a sub-resource, such as
  `POST .../publication`, `POST .../submission`, or `POST .../revocation`.
  Do not expose an arbitrary state setter.
- Represent identifiers as UUIDv7 and timestamps as RFC 3339 UTC.

Provider callbacks are management routes outside `/api/v1` and use their own
authentication contract. A workforce JWT security scheme must never be reused
implicitly for candidate exam tokens or provider callbacks.

## HTTP Semantics

| Concern | Convention |
|---|---|
| Safe read | `GET`; cacheable only when the resource's confidentiality permits it |
| Full replacement | `PUT`; idempotent |
| Partial update | `PATCH`; only explicitly named merge fields |
| Creation or transition | `POST` |
| Synchronous success | `200 OK` |
| Created resource | `201 Created` with `Location` |
| Accepted asynchronous work | `202 Accepted` |
| Success without a representation | `204 No Content` |
| Invalid syntax/input | `400 Bad Request` |
| Missing authentication | `401 Unauthorized` |
| Disallowed action | `403 Forbidden`, unless non-disclosure requires `404` or a generic refusal |
| Missing or tenant-invisible resource | `404 Not Found` |
| State/version conflict | `409 Conflict` |
| Deliberately destroyed material | `410 Gone` |
| Domain invariant violation | `422 Unprocessable Content` |
| Active lockout | `423 Locked` |
| Rate limit | `429 Too Many Requests` |
| Temporary degradation | `503 Service Unavailable` with `Retry-After` |

Cross-tenant resources return `404`. PIN refusal is generic and must not reveal
whether a PIN, candidate, or assignment exists. Candidate-facing, PIN, OTP, and
token responses use `Cache-Control: no-store`; suitable reference data may use a
short `max-age` with `ETag`.

Requests and responses use `application/json`. Enforce `Content-Type`, cap body
size, reject unknown properties, perform syntactic validation at the boundary,
and validate tenant ownership before mutation. Domain invariants remain in
domain types and policies. Bounded integer configuration exposes `minimum`,
`maximum`, and `default` schema metadata; omission selects the documented
default.

## Contract Shape

Each `Endpoint` declares one stable `operationId` in the form
`<module>.<verbNoun>`, plus its path, method, audience/security scheme, media
types, success responses, and allowlisted problem responses. Immutable
`Request` and `Response` records are the schema source; do not maintain a second
hand-written payload model.

Public schemas contain API DTOs only. Never expose domain or persistence types,
answer keys, PINs, OTPs, tokens, internal exception details, SQL, or unbounded
maps. Problem responses follow RFC 9457 and include `status`, a stable `code`,
and `correlationId`; the shared implementation contract is owned by
`FEAT-PLAT-003`.

## Versioning and Compatibility

- Keep additive compatible changes in the current major version.
- Move removed operations/fields, narrowed types, newly required fields, or
  changed enum meaning to a new URI major version.
- Announce retirement with `Deprecation` and `Sunset` headers at least 90 days
  before removal.
- Compare generated OpenAPI against the immutable released baseline in CI stage
  9. That breaking-change gate is owned by a later operations feature.

## Pagination and Filtering

Collection routes use keyset pagination, never offset/page-number pagination.
The default ordering tuple is:

```text
(tenant_id, created_at DESC, id DESC)
```

Return the standard envelope:

```json
{
  "items": [],
  "nextCursor": "opaque-value",
  "hasMore": false
}
```

Cursors are opaque and preserve the documented tenant-scoped ordering tuple.
Every collection slice declares an explicit filter allowlist; there is no
generic query language. Every allowed filter retains tenant scoping and must
have an index-supported query plan. Document the exact cursor tuple and filter
allowlist in the operation contract.

## OpenAPI Generation

Later endpoint features install the Spring WebFlux-compatible OpenAPI tooling
for the repository's Spring Boot line and generate one deterministic OpenAPI 3
document at `build/generated/openapi/openapi.yaml`. Functional routes declare
metadata explicitly so generation does not infer an incomplete contract from
handler reflection.

Generation must validate unique operation IDs, complete route coverage, valid
schemas, audience-specific security, declared problem responses, `/api/v1`
versioning, standard collection envelopes, and absence of internal or
secret-bearing fields. Sort paths/components and remove timestamps and
host-specific data so identical source produces identical bytes. Publish the
generated document as build evidence; never commit or hand-edit generated
content under `build/`.

The generated document is evidence of the slice contracts, not a substitute
for them. An endpoint that cannot be represented completely fails generation
instead of disappearing from the API description.
