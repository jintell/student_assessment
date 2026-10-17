# TASK-PLAT4-DEFECT-005 Verification-Register Mis-Citation

Status: OPEN — local evidence mapping corrected; next-baseline amendment and owner acknowledgements pending.

Recipients: Architecture Owner and Plan Owner. Raised by FEAT-PLAT-004, republished 2026-10-16 for P10.10.

## Defect and Adopted Resolution

Plan §14.4 assigns ARC-VERIFY-012/013/014 to outbox propagation, event compatibility and idempotency inventory. Architecture §19.8 instead assigns those IDs to the OpenAPI breaking-change diff, ProblemDetail fault injection and webhook controls respectively. They cannot be reused to make outbox traceability appear complete.

Use ARC-VERIFY-006 for this feature's composite-write/database integration limb, with the static R7 limb owned by FEAT-PLAT-001. Use the actual local `eventSchemaCompatibility`, `verifyEventSchemas` and `verifyIdempotencyInventory` gate names where the architecture has no registered ID. Preserve the correct API, FEAT-PLAT-003 and FEAT-NOTF-003 ownership of -012/-013/-014.

Evidence: [P1.5 ownership correction](../evidence/FEAT-PLAT-004/phase-1-discovery.md#p15-verification-ownership-correction), [propagation rule](../async-propagation-rule.md), `build.gradle.kts` gate declarations and plan §19 delivery-evidence rows. P10.11 updates evidence only; it does not silently amend §14.4 or allocate new architecture identifiers.

## Requested Acknowledgement

Architecture Owner confirms register ownership and decides whether new identifiers are needed. Plan Owner acknowledges and corrects §14.4 in the next approved revision. Neither receipt is available. See the [nine-record handover](../evidence/FEAT-PLAT-004/P10.10-baseline-defect-handoff.md); repository publication is not acknowledgement.
