# Idempotency Inventory

The normative operation → key → store → owner map is [contracts/idempotency-inventory.yaml](../contracts/idempotency-inventory.yaml), transcribed from architecture §14.6. Keep the thirteen baseline operations; extend the manifest for newly approved operations rather than maintaining a second table here. `docs/idempotency.md` describes the separate HTTP Idempotency-Key contract.

Every row names the stable business key and durable store. Event-ID deduplication is necessary but not sufficient: two events can describe one business action. Each consuming feature owns its business constraint/state guard and transactional effect, following the [consumer guide](consumer-authoring.md).

## Proof and Delivery Status

`provingTest` names a real class and method; `additionalProofs` retain supporting references. The outbox row now points to the PostgreSQL crash-after-commit/duplicate integration test, with rollback and out-of-order proofs. Its infrastructure proof is implemented; this does **not** mark FEAT-PLAT-004 shipped or prove every consumer's business key. `contracts/feature-delivery-status.yaml` remains unchanged because production assembly/staging gates are open. Unimplemented feature-owned rows remain `pending-owner-delivery`, not fabricated evidence.

## Keep the Map Current

```bash
./gradlew verifyIdempotencyInventory
./gradlew test --tests '*IdempotencyInventoryCheckerTest'
./gradlew integrationTest --tests '*OutboxFailureInjectionIntegrationTest'
```

P4.17's `verifyIdempotencyInventory` is a dependency of `check`. It rejects duplicate IDs, missing baseline operations, blank operation/key/store/owner and malformed feature IDs. For owners listed as shipped, it requires `proofStatus: implemented` and a primary proving-test class/method in `src/test/java` or `src/integrationTest/java`. The integration-source lookup supports real database evidence rather than requiring a unit-test stand-in.

The gate checks source references, not execution or proof quality, and does not validate every `additionalProofs` entry. Run the referenced tests and retain successful results before owner review/shipping; a green inventory gate alone does not establish idempotency. Changes to keys, store semantics or owners require architecture/feature review and corresponding duplicate/concurrency/crash tests. Never mark an owner shipped or weaken its business key merely to satisfy the checker.
