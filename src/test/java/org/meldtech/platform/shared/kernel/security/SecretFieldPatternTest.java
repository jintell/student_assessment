package org.meldtech.platform.shared.kernel.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretFieldPatternTest {

    @Test
    void matchesCompleteNormalizedSecretSegments() {
        assertTrue(SecretFieldPattern.isSecretField("apiKey"));
        assertTrue(SecretFieldPattern.isSecretField("access_token"));
        assertTrue(SecretFieldPattern.isSecretField("pin-ciphertext"));
        assertTrue(SecretFieldPattern.isSecretField("passwordHash"));
        assertTrue(SecretFieldPattern.isSecretField("authorization.header"));
        assertTrue(SecretFieldPattern.isSecretField("oneTimeOtp"));
    }

    @Test
    void doesNotMatchUnrelatedSubstrings() {
        assertFalse(SecretFieldPattern.isSecretField("tokenizerVersion"));
        assertFalse(SecretFieldPattern.isSecretField("monkeyBusiness"));
        assertFalse(SecretFieldPattern.isSecretField("keynoteSpeaker"));
        assertFalse(SecretFieldPattern.isSecretField("pinpoint"));
    }
}
