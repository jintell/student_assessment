# TASK-PLAT4-DEFECT-006 Unsupported Per-Aggregate Ordering Wording

Status: OPEN — order-insensitive local contract adopted; Architecture Owner acknowledgement and §11.2 wording amendment pending.

Recipient: Architecture Owner. Raised by FEAT-PLAT-004, republished 2026-10-16 for P10.10.

## Defect and Adopted Resolution

Architecture §11.2 describes a partition key and single consumer per key, but §11.4's shared quorum queue with prefetch 32 does not implement that topology. The plan also refers to ordered-per-key semantics. Oldest-first relay claiming and sequential publishing do not prove per-key effect ordering after redelivery or concurrent consumption.

The [normative delivery contract](../../contracts/events/DELIVERY-CONTRACT.md) requires at-least-once, no ordering guarantee, idempotent apply and version guards. The local out-of-order integration proof is `OutboxFailureInjectionIntegrationTest.outOfOrderDeliveryRetainsTheNewestAggregateVersion`. REQ-IAM-018 precedence stays in its owning transaction. The [consistent-hash seam](../architecture/outbox-ordering-seam.md) remains unused and lists the approval, provisioning, capacity and recovery prerequisites for any stronger future contract.

## Requested Acknowledgement

Architecture Owner acknowledges the local resolution and corrects §11.2's single-consumer-per-key wording in the next baseline, with corresponding plan reconciliation. No ordering plugin/topology is implemented here. Existing event-evolution/idempotency DoR does not specifically acknowledge this wording defect. Receipt remains pending in the [nine-record handover](../evidence/FEAT-PLAT-004/P10.10-baseline-defect-handoff.md).
