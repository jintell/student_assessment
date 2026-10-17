# Event Authoring

Normative producer guidance for FEAT-PLAT-004 P10.1. Read the [delivery contract](../contracts/events/DELIVERY-CONTRACT.md) and [compatibility specification](../contracts/events/COMPATIBILITY.md) before adding an event. Business events belong to their publishing feature; `platform.ReferenceEvent.v1` is only a test fixture.

## Name and Minimise

Use `<context>.<Event>.v<n>`: lowercase alphanumeric context beginning with a letter, PascalCase event name, positive integer version without a leading zero. For example, `grading.AttemptGraded.v1`. Keep the filename, schema `$id` (`urn:meldtech:event:<type>`) and `properties.eventType.const` aligned.

Publish the minimum identifiers and facts required by named consumers, not an entity dump. Each leaf needs a stable `x-semantic-id`, meaningful description, `x-required-by` naming a subset of root `x-consumers`, and `x-data-classification` (`identifier`, `operational`, or `personal`). Personal data additionally requires `x-identifier-insufficient-reason`; prefer an authorized identifier lookup. Do not put PINs, OTPs, tokens, passwords, credential hashes or secrets in payloads, schema examples or descriptions. Identifier/personal leaves may not have examples, defaults or constants. Close objects with `additionalProperties: false` and bound strings/arrays.

## Register the Contract

1. Add `contracts/events/<type>.json`, using the reference schema's metadata shape, JSON Schema 2020-12, explicit `required`, owner and consumer declarations. Do not mark a real event `x-test-fixture`.
2. Supply an `IntegrationEvent` record and its schema-verification registration. Current tooling uses `RegisteredEventContract` and an explicit event-class list in `verifyEventSchemas` in `build.gradle.kts`; it does not discover every feature's event automatically. Keep feature/domain dependencies inward: if the infrastructure annotation would violate a module boundary, agree a contract-test adapter with the platform owner rather than importing infrastructure into the domain.
3. Add each consumer and exact version to `contracts/events/consumers.yaml`. For enum expansion, every affected consumer needs an explicit default branch and real proving test. Run those tests; current checker metadata alone is not execution proof.
4. Preserve the previous approved schema under `contracts/events/baseline/`. For a newly registered version, retain its approved initial snapshot for future comparisons. Never edit an old baseline alongside a breaking change to make CI green. Baseline promotion requires reviewed release ownership.
5. Wire the owning producer/consumer through the approved adapters, provision the schema for runtime validation, and add transaction/rollback, payload rejection, duplicate and version tests. A schema file alone does not wire a producer or consumer.

## Append in the Business Transaction

Use `shared.kernel.outbox.OutboxWriter.append(tenantId, actor, message)` in the same reactive transaction as the business change, under the module role and tenant/actor/correlation context. Compose the returned publisher into the transaction; do not detach it with `subscribe()`, call `block()`, create a second transaction or publish to RabbitMQ from the request handler. The adapter rejects missing reactive transactions. Preserve the original event ID on retries/redrive and carry controlled occurrence time and aggregate identity. Row/message context carries correlation and W3C trace metadata without metric labels containing identifiers.

Answer acceptance intentionally writes **no outbox row** under ADR-009. Follow the [closed synchronous exception boundary](async-propagation-rule.md) rather than inventing an exception for convenience.

## Evolve and Verify

Within one version, optional fields may be added only when all checks and consumers support them. Field removal/rename, optional-to-required changes, type narrowing, tighter bounds, format/semantic changes and enum removals require a new version. Enum additions require every registered consumer's default handling. Publish both versions during migration, opt consumers into each version explicitly, and retire the old version only after at least 30 consecutive days of zero consumption plus owner approval. Unknown versions go to a distinct dead-letter path, never silent success.

```bash
./gradlew verifyEventSchemas eventSchemaCompatibility verifyEventPayloadPolicy
./gradlew ciStage9
```

Stage 9 rejects record/schema drift, incompatible changes against the checked-in baseline, incomplete registration, unsupported enum expansion, retirement without evidence, credential-shaped properties and missing minimisation metadata. Run the owning feature's tests too; schema compatibility does not prove business semantics.

## Current Tooling Limits

The compatibility design calls for a merge-base comparison; the current Gradle task instead reads the checked-in `baseline/` directory. Protect baseline changes in review; an automatic merge-base safeguard is not claimed. `EventSchemaGenerator` verifies selected record component names/types and currently requires all components to be required. Thus a theoretically compatible optional addition may still fail the record gate: do not weaken requiredness or bypass CI; obtain platform support or a new version. It is not a full JSON Schema generator/validator. Enum-default declarations are checked for nonblank metadata, not test execution. Runtime validation and production assembly must be reviewed for the feature's actual schema shape; current outbox assembly/staging gates remain open.
