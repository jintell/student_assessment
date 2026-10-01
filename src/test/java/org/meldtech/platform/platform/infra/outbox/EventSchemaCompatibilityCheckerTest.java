package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventSchemaCompatibilityCheckerTest {

    private static final String TYPE = "platform.ReferenceEvent.v1";

    @Test
    void optionalFieldAndNewEventTypeAreCompatible(@TempDir Path temporaryDirectory)
            throws Exception {
        Fixture fixture = fixture(temporaryDirectory, "optional");
        fixture.writeBaseline(TYPE, schema(TYPE, "\"type\":\"string\"", "value"));
        fixture.writeCurrent(
                TYPE,
                schema(TYPE, "\"type\":\"string\"", "value")
                        .replace(
                                "\"value\": {",
                                "\"extra\": {\"type\":\"string\","
                                        + "\"description\":\"extra\","
                                        + "\"x-semantic-id\":\"extra\","
                                        + "\"x-required-by\":[\"platform\"]},"
                                        + "\"value\": {"));
        fixture.writeCurrent(
                "platform.NewEvent.v1",
                schema("platform.NewEvent.v1", "\"type\":\"string\"", "value"));

        assertThatNoException().isThrownBy(fixture::verify);
    }

    @Test
    void removalAndRenameAreRejected(@TempDir Path temporaryDirectory) throws Exception {
        Fixture removal = fixture(temporaryDirectory, "removal");
        removal.writeBaseline(TYPE, schema(TYPE, "\"type\":\"string\"", "value"));
        removal.writeCurrent(TYPE, schema(TYPE, "\"type\":\"string\"", "renamed"));

        assertThatIllegalStateException()
                .isThrownBy(removal::verify)
                .withMessageContaining("COMPAT_FIELD_REMOVED");
    }

    @Test
    void typeNarrowingAndSemanticChangeAreRejected(@TempDir Path temporaryDirectory)
            throws Exception {
        Fixture narrowing = fixture(temporaryDirectory, "narrowing");
        narrowing.writeBaseline(TYPE, schema(TYPE, "\"type\":[\"string\",\"null\"]", "value"));
        narrowing.writeCurrent(TYPE, schema(TYPE, "\"type\":\"string\"", "value"));

        assertThatIllegalStateException()
                .isThrownBy(narrowing::verify)
                .withMessageContaining("COMPAT_TYPE_NARROWED");

        Fixture semantics = fixture(temporaryDirectory, "semantics");
        semantics.writeBaseline(TYPE, schema(TYPE, "\"type\":\"string\"", "value"));
        semantics.writeCurrent(
                TYPE,
                schema(TYPE, "\"type\":\"string\"", "value")
                        .replace("\"description\":\"value\"", "\"description\":\"changed\""));
        assertThatIllegalStateException()
                .isThrownBy(semantics::verify)
                .withMessageContaining("COMPAT_SEMANTICS_CHANGED");
    }

    @Test
    void enumAdditionRequiresEveryConsumerDefault(@TempDir Path temporaryDirectory)
            throws Exception {
        Fixture fixture = fixture(temporaryDirectory, "enum");
        fixture.writeBaseline(TYPE, schema(TYPE, "\"type\":\"string\",\"enum\":[\"A\"]", "value"));
        fixture.writeCurrent(
                TYPE, schema(TYPE, "\"type\":\"string\",\"enum\":[\"A\",\"B\"]", "value"));

        assertThatIllegalStateException()
                .isThrownBy(fixture::verify)
                .withMessageContaining("COMPAT_ENUM_DEFAULT_MISSING");
    }

    @Test
    void retirementRequiresThirtyDaysZeroConsumptionAndApproval(@TempDir Path temporaryDirectory)
            throws Exception {
        Fixture fixture = fixture(temporaryDirectory, "retirement");
        fixture.writeBaseline(TYPE, schema(TYPE, "\"type\":\"string\"", "value"));

        assertThatIllegalStateException()
                .isThrownBy(fixture::verify)
                .withMessageContaining("Missing compatibility evidence");

        Files.writeString(
                fixture.retirements.resolve(TYPE + ".yaml"),
                """
                consumptionCount: 0
                ownerApproved: true
                zeroConsumptionStart: 2026-08-01
                zeroConsumptionEnd: 2026-09-01
                """);
        assertThatNoException().isThrownBy(fixture::verify);
    }

    private static Fixture fixture(Path temporaryDirectory, String name) throws Exception {
        Path root = temporaryDirectory.resolve(name);
        Path baseline = Files.createDirectories(root.resolve("baseline"));
        Path current = Files.createDirectories(root.resolve("current"));
        Path retirements = Files.createDirectories(root.resolve("retirements"));
        Path consumers = root.resolve("consumers.yaml");
        Files.writeString(consumers, "versions: []\n");
        return new Fixture(baseline, current, consumers, retirements);
    }

    private static String schema(String eventType, String valueSchema, String valuePropertyName) {
        return """
                {
                  "$id":"urn:meldtech:event:%1$s",
                  "type":"object",
                  "additionalProperties":false,
                  "x-owner":"FEAT-PLAT-004",
                  "x-consumers":["platform"],
                  "required":["eventType","%3$s"],
                  "properties":{
                    "eventType": {
                      "type":"string","const":"%1$s",
                      "description":"event type",
                      "x-semantic-id":"event-type",
                      "x-required-by":["platform"]
                    },
                    "%3$s": {
                      %2$s,
                      "description":"value",
                      "x-semantic-id":"value",
                      "x-required-by":["platform"]
                    }
                  }
                }
                """
                .formatted(eventType, valueSchema, valuePropertyName);
    }

    private record Fixture(Path baseline, Path current, Path consumers, Path retirements) {

        private void writeBaseline(String eventType, String schema) throws Exception {
            Files.writeString(baseline.resolve(eventType + ".json"), schema);
        }

        private void writeCurrent(String eventType, String schema) throws Exception {
            Files.writeString(current.resolve(eventType + ".json"), schema);
        }

        private void verify() {
            EventSchemaCompatibilityChecker.verify(baseline, current, consumers, retirements);
        }
    }
}
