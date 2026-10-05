package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.platform.shared.kernel.identity.CandidateId;

class CandidateIdentifierHasherTest {

    private static final CandidateId CANDIDATE_ID =
            CandidateId.parse("018f47c1-7b58-7a9b-8f15-20f69d98540d");

    @Test
    void hashesDeterministicallyWithinOneEnvironment(@TempDir Path directory) throws IOException {
        Path secret = writeSecret(directory, "environment-a", 'a');
        CandidateIdentifierHasher hasher =
                CandidateIdentifierHasher.from(
                        new ObservabilityProperties.CandidateHash(secret.toString()));

        String first = hasher.hash(CANDIDATE_ID);
        String second = hasher.hash(CANDIDATE_ID);

        assertThat(first).isEqualTo(second).matches("h1\\.[A-Za-z0-9_-]{43}");
        assertThat(first).doesNotContain(CANDIDATE_ID.toString()).doesNotContain("aaaaaaaa");
    }

    @Test
    void producesDifferentHashesWithDifferentEnvironmentSecrets(@TempDir Path directory)
            throws IOException {
        CandidateIdentifierHasher environmentA =
                CandidateIdentifierHasher.fromSecretReference(
                        writeSecret(directory, "environment-a", 'a'));
        CandidateIdentifierHasher environmentB =
                CandidateIdentifierHasher.fromSecretReference(
                        writeSecret(directory, "environment-b", 'b'));

        assertThat(environmentA.hash(CANDIDATE_ID)).isNotEqualTo(environmentB.hash(CANDIDATE_ID));
    }

    @Test
    void failsClosedWithoutDisclosingTheSecretReference(@TempDir Path directory)
            throws IOException {
        Path shortSecret = directory.resolve("sensitive-secret-name");
        Files.writeString(shortSecret, "short", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> CandidateIdentifierHasher.fromSecretReference(shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Candidate hash secret is unavailable or shorter than 32 bytes")
                .hasMessageNotContaining(shortSecret.toString())
                .hasNoCause();
        assertThatThrownBy(
                        () ->
                                CandidateIdentifierHasher.from(
                                        new ObservabilityProperties.CandidateHash(" ")))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
    }

    private static Path writeSecret(Path directory, String name, char value) throws IOException {
        return Files.writeString(
                directory.resolve(name),
                String.valueOf(value).repeat(32),
                StandardCharsets.US_ASCII);
    }
}
