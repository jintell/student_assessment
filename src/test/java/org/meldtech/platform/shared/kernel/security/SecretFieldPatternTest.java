package org.meldtech.platform.shared.kernel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
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

    @Test
    void permitsOnlyTheApprovedPolicyKeyLeaf() {
        assertEquals(Set.of("policy_key"), SecretFieldPattern.permittedKeyFields());
        assertFalse(SecretFieldPattern.isSecretField("policy_key"));
        assertFalse(SecretFieldPattern.isSecretField("policyKey"));
        assertFalse(SecretFieldPattern.isSecretField("policy-key"));
        assertFalse(SecretFieldPattern.isSecretField("retention.policy_key"));
    }

    @Test
    void rejectsEveryOtherKeyFieldAndSecretParentPath() {
        assertTrue(SecretFieldPattern.isSecretField("api_key"));
        assertTrue(SecretFieldPattern.isSecretField("encryption_key"));
        assertTrue(SecretFieldPattern.isSecretField("privateKey"));
        assertTrue(SecretFieldPattern.isSecretField("candidate_key"));
        assertTrue(SecretFieldPattern.isSecretField("secret.policy_key"));
        assertTrue(SecretFieldPattern.isSecretField("policy_key.secret"));
        assertTrue(SecretFieldPattern.isSecretField("policy.key"));
        assertTrue(SecretFieldPattern.isSecretField("policyKeyHash"));
    }
}
