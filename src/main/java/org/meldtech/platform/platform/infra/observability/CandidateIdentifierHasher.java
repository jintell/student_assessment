package org.meldtech.platform.platform.infra.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.meldtech.platform.shared.kernel.identity.CandidateId;

final class CandidateIdentifierHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final byte[] DOMAIN_SEPARATOR =
            "cbt:candidate-telemetry:v1\0".getBytes(StandardCharsets.US_ASCII);

    private final SecretKey secretKey;

    private CandidateIdentifierHasher(SecretKey secretKey) {
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey");
    }

    static CandidateIdentifierHasher from(ObservabilityProperties.CandidateHash configuration) {
        Objects.requireNonNull(configuration, "configuration");
        String secretReference = configuration.secretReference();
        if (secretReference == null || secretReference.isBlank()) {
            throw unavailableSecret();
        }
        return fromSecretReference(Path.of(secretReference));
    }

    static CandidateIdentifierHasher fromSecretReference(Path secretReference) {
        Objects.requireNonNull(secretReference, "secretReference");
        byte[] secret = readSecret(secretReference);
        try {
            if (secret.length < MINIMUM_SECRET_BYTES) {
                throw unavailableSecret();
            }
            return new CandidateIdentifierHasher(new SecretKeySpec(secret, ALGORITHM));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    String hash(CandidateId candidateId) {
        Objects.requireNonNull(candidateId, "candidateId");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            mac.update(DOMAIN_SEPARATOR);
            byte[] digest = mac.doFinal(candidateId.toString().getBytes(StandardCharsets.US_ASCII));
            return "h1." + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Candidate identifier hashing is unavailable");
        }
    }

    private static byte[] readSecret(Path secretReference) {
        try {
            if (!Files.isRegularFile(secretReference) || !Files.isReadable(secretReference)) {
                throw unavailableSecret();
            }
            return Files.readAllBytes(secretReference);
        } catch (IOException | SecurityException exception) {
            throw unavailableSecret();
        }
    }

    private static IllegalStateException unavailableSecret() {
        return new IllegalStateException(
                "Candidate hash secret is unavailable or shorter than 32 bytes");
    }
}
