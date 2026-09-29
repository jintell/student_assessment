# Error Authoring

Status: normative guide for adding or changing platform error responses.

## Add a Code

1. Choose a stable, semantic code in the `CBT-PLAT-*` namespace. Describe the
   client-visible condition, not the Java exception or implementation that
   happened to detect it.
2. Add exactly one entry to `src/main/resources/error-catalogue.yaml` with a
   unique type URI below `https://errors.meld-tech.com/problems/`, a fixed
   title, an HTTP error status, a fixed non-sensitive detail, and the closed
   extension definition.
3. If a throwable maps directly to the code, add its class-to-code mapping in
   `KernelErrorConfiguration`. Prefer a boundary or domain exception whose
   meaning is stable; do not use an exception message as the discriminator.
4. Add tests for the mapping and any declared extension. Exercise the actual
   WebFlux error boundary when the behavior is transport-visible.
5. Run `./gradlew generateErrorCatalogue problemDetailAllowlistTest`. Review
   the generated OpenAPI schema and client catalogue, then update
   `docs/error-catalogue.md` under its compatibility rules.

Do not define a second code constant, response DTO, hand-written OpenAPI
schema, or local error-body factory. The YAML catalogue is the source for
runtime definitions, generated schemas, examples, and client documentation.

## Safe Detail

`detail` is public text selected from the catalogue by `code`. It may explain
what a client can safely do next, but it may not contain or interpolate:

- an exception message, class, stack trace, or internal state;
- SQL, relation, provider, host, deployment, or library details;
- a PIN, OTP, token, secret, password, key, authorization value, or request
  payload; or
- personal data or a resource fact that authorization policy must conceal.

Never pass `Throwable.getMessage()` or a caught provider response to
`ProblemDetailDocument`, an extension, or a response writer. Log the internal
failure through the reviewed handler with the correlation identifier; return
only the catalogue definition. An unmapped exception deliberately becomes
`CBT-PLAT-INTERNAL` and increments `problem_detail_unmapped_total`.

## Extensions

Extensions must be declared on the catalogue entry, use an approved primitive
type, and define whether they are required. Add minimum, maximum, length,
pattern, or enum constraints where applicable. Do not redefine the base
members `type`, `title`, `status`, `code`, `detail`, `instance`, or
`correlationId`, and do not use an unbounded object or map as an extension.

The mapper rejects supplied extension names that the selected entry does not
declare, missing required extensions, and values of the wrong primitive type.
Generated schemas set `additionalProperties: false`.

## Blocking Checks

The CI stage 10 allowlist limb rejects:

- a production `CBT-PLAT-*` code absent from the catalogue;
- construction of `ProblemDetailDocument` outside `ProblemDetailMapper`;
- a direct problem response write outside the reviewed global handler;
- uncatalogued response fields or extensions;
- stack traces, SQL, provider messages, and secret-bearing values in emitted
  responses; and
- a mapper failure path that leaks, hangs, or terminates without the generic
  response.

Catalogue validation also rejects an invalid code namespace, a duplicate or
out-of-base type URI, missing or unsupported fields, a non-error HTTP status,
an unsupported extension type, and a missing or extended
`CBT-PLAT-INTERNAL` fallback.

The published response contract and compatibility policy are in
[`error-catalogue.md`](error-catalogue.md).
