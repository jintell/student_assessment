package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventPayloadPolicyCheckerTest {

    @Test
    void acceptsRegisteredSchemas() {
        assertThatNoException()
                .isThrownBy(() -> EventPayloadPolicyChecker.verify(Path.of("contracts/events")));
    }

    @Test
    void rejectsCredentialShapedFieldUsingKernelPattern(@TempDir Path directory)
            throws IOException {
        writeSchema(directory, "pinCiphertext", "operational", "");

        assertThatIllegalStateException()
                .isThrownBy(() -> EventPayloadPolicyChecker.verify(directory))
                .withMessageContaining("PAYLOAD_SECRET_FIELD")
                .withMessageContaining("pinCiphertext");
    }

    @Test
    void rejectsPersonalFieldWithoutIdentifierJustification(@TempDir Path directory)
            throws IOException {
        writeSchema(directory, "emailAddress", "personal", "");

        assertThatIllegalStateException()
                .isThrownBy(() -> EventPayloadPolicyChecker.verify(directory))
                .withMessageContaining("PAYLOAD_PERSONAL_JUSTIFICATION")
                .withMessageContaining("emailAddress");
    }

    @Test
    void acceptsPersonalFieldWithNamedConsumerAndJustification(@TempDir Path directory)
            throws IOException {
        writeSchema(
                directory,
                "emailAddress",
                "personal",
                ",\"x-identifier-insufficient-reason\":\"provider requires destination\"");

        assertThatNoException().isThrownBy(() -> EventPayloadPolicyChecker.verify(directory));
    }

    @Test
    void rejectsFieldOwnedByUnknownConsumer(@TempDir Path directory) throws IOException {
        writeSchema(
                directory, "referenceId", "identifier", ",\"x-required-by\":[\"notification\"]");

        assertThatIllegalStateException()
                .isThrownBy(() -> EventPayloadPolicyChecker.verify(directory))
                .withMessageContaining("PAYLOAD_FIELD_OWNER");
    }

    private static void writeSchema(
            Path directory, String propertyName, String classification, String extra)
            throws IOException {
        String requiredBy =
                extra.contains("x-required-by") ? "" : ",\"x-required-by\":[\"platform\"]";
        Files.writeString(
                directory.resolve("platform.PolicyEvent.v1.json"),
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "x-consumers":["platform"],
                  "properties":{
                    "%s":{
                      "type":"string",
                      "maxLength":128,
                      "x-data-classification":"%s"%s%s
                    }
                  }
                }
                """
                        .formatted(propertyName, classification, requiredBy, extra));
    }
}
