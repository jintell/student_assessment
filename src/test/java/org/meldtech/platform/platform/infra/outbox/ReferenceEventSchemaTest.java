package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ReferenceEventSchemaTest {

    private static final Path SCHEMA = Path.of("contracts/events/platform.ReferenceEvent.v1.json");

    @Test
    void referenceSchemaIsClosedVersionedAndExplicitlyTestOnly() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(Files.readString(SCHEMA));

        assertThat(schema.path("$id").stringValue()).endsWith("platform.ReferenceEvent.v1");
        assertThat(schema.path("x-test-fixture").asBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.at("/properties/eventType/const").stringValue())
                .isEqualTo("platform.ReferenceEvent.v1");
        assertThat(schema.path("properties").properties())
                .allSatisfy(
                        property -> {
                            assertThat(property.getValue().path("x-semantic-id").stringValue())
                                    .isNotBlank();
                            assertThat(property.getValue().path("x-required-by").isArray())
                                    .isTrue();
                            assertThat(
                                            property.getValue()
                                                    .path("x-data-classification")
                                                    .stringValue())
                                    .isIn("identifier", "operational", "personal");
                        });
    }
}
