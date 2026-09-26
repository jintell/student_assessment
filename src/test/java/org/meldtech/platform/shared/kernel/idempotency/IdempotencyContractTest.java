package org.meldtech.platform.shared.kernel.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdempotencyContractTest {

    @Test
    void keyAcceptsOnlyBoundedVisibleAsciiAndDoesNotRenderItsValue() {
        IdempotencyKey key = new IdempotencyKey("client-operation-42");

        assertFalse(key.toString().contains(key.value()));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(" key"));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("key\nvalue"));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("x".repeat(129)));
    }

    @Test
    void fingerprintContainsOnlyTheOneWayDigest() {
        RequestFingerprint fingerprint = RequestFingerprint.sha256("POST\nroute.id\n{}\n");

        assertEquals(64, fingerprint.hex().length());
        assertEquals(fingerprint, RequestFingerprint.parseHex(fingerprint.hex()));
    }

    @Test
    void storedResponseIsBoundedAndDefensivelyCopiesBody() {
        byte[] body = "response".getBytes(StandardCharsets.UTF_8);
        StoredResponse response =
                new StoredResponse(
                        201, Map.of("Location", "/resource/1"), "application/json", body);

        body[0] = 'X';
        assertArrayEquals("response".getBytes(StandardCharsets.UTF_8), response.body());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StoredResponse(
                                200, Map.of("Authorization", "secret"), "text/plain", new byte[0]));
    }

    @Test
    void outcomeSetHasExactlyThreeVariants() {
        assertEquals(3, ReservationOutcome.class.getPermittedSubclasses().length);
    }
}
