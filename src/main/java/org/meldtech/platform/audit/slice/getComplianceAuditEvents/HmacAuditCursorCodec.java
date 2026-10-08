package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class HmacAuditCursorCodec implements AuditCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] key;
    private final ObjectMapper objectMapper;

    public HmacAuditCursorCodec(byte[] key, ObjectMapper objectMapper) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException(
                    "cursor authentication key must be at least 256 bits");
        }
        this.key = Arrays.copyOf(key, key.length);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String encode(ComplianceCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
        } catch (JacksonException exception) {
            throw new IllegalStateException("unable to encode compliance cursor", exception);
        }
    }

    @Override
    public ComplianceCursor decode(String encoded) {
        try {
            String[] parts = encoded.split("[.]", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid compliance cursor");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] suppliedMac = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(mac(payload), suppliedMac)) {
                throw new IllegalArgumentException("invalid compliance cursor");
            }
            return objectMapper.readValue(payload, ComplianceCursor.class);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new IllegalArgumentException("invalid compliance cursor", exception);
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("JVM does not provide HMAC-SHA-256", impossible);
        }
    }
}
