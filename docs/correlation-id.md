# Correlation Identifier

Status: support and operations guide for tracing one request across platform
diagnostic surfaces.

## Definition

The correlation identifier is a canonical uppercase ULID matching
`^[0-7][0-9A-HJKMNP-TV-Z]{25}$`. It is an opaque diagnostic value, not a user,
tenant, session, attempt, or authorization identifier.

An HTTP client may send one `X-Correlation-Id` value. The platform accepts it
only when it matches the strict ULID form. A missing, repeated, malformed,
lowercase, oversized, or control-character-bearing value is replaced with a
new ULID. Rejected input is never echoed or logged.

## Where It Appears

For a request, the resolved value is carried through Reactor context and
appears in:

- the `X-Correlation-Id` response header on success and failure;
- `correlationId` on an RFC 9457 problem response;
- the `correlationId` structured-log field on every request log line;
- the `correlationId` diagnostic attribute on the request trace; and
- outbox and integration-event metadata when the request crosses an
  asynchronous boundary.

Reactive context propagation preserves the same value across supported
scheduler hops and clears it when processing terminates. It must not be stored
in a thread-local or recovered from a thread name.

Correlation identifiers are not metric labels. Problem counters use bounded
labels such as error code or outcome. A sampled Prometheus exemplar carries
`trace_id` and `span_id`; the linked trace contains `correlationId`.

## Trace a Failure

1. Obtain the correlation identifier from the client's response header or
   problem body. Validate its ULID form before using it as a search value.
2. Search protected structured logs for exact `correlationId` equality. Confirm
   the time window, route, environment, and final `errorCode`; do not search by
   fragments.
3. Open the associated trace using its trace identifier, or start from a
   metric exemplar and follow its `trace_id`. Confirm the trace's
   `correlationId` attribute matches the client value.
4. Follow downstream spans and outbox/event metadata with the same correlation
   identifier. A new trace may begin at an asynchronous consumer, but the
   application correlation value remains the join key.
5. Use internal exception class and protected diagnostic fields to investigate.
   Do not expect the client `detail` to contain the internal cause; generic
   errors intentionally disclose only the fixed catalogue text.

For a rising `problem_detail_unmapped_total` rate, continue with
[`runbook-problem-detail-unmapped.md`](runbook-problem-detail-unmapped.md).

## Data Classification

The identifier is randomly generated diagnostic metadata and contains no
personal data, tenant data, business key, credential, or encoded request
content. Do not derive it from an email address, name, actor ID, candidate ID,
tenant ID, IP address, token, or payload. Do not attach personal or secret data
to it in logs, span attributes, or metric labels.

Possessing a correlation identifier grants no access. Support tools must still
enforce their normal environment and log/trace authorization boundaries, and
client-visible lookup endpoints must not be created for it.
