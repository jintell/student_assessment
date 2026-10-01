# TASK-PLAT4-OBS-003 Event Payload Secret-Scan Coverage

Status: **OPEN - CONTROL IMPLEMENTED HERE AND HANDED OFF**

Owner: `FEAT-SEC-001`

Raised by: `FEAT-PLAT-004`

## Gap

The stage 10 leak suite covered logs, audit payloads, and error responses but
did not inspect serialized integration-event payloads. A credential could
therefore cross the broker boundary without exercising the existing scan.

## Control Shipped By This Feature

The integration suite writes schema-validated serialized event payloads to a
dedicated build artifact. Stage 10 scans every captured JSON property path
with the shared kernel `SecretFieldPattern` and fails closed when the capture
is absent or empty. Deliberately planted `pin` and nested `otp` fields prove
that the gate blocks without printing the credential value.

## Owner Follow-Up

`FEAT-SEC-001` owns this extension as part of the platform-wide secret-leak
suite. New integration-event paths must contribute a representative payload
capture, and the security feature must preserve this scanner when it
consolidates stage 10 controls.
