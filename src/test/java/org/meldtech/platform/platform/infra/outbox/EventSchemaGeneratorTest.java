package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.outbox.IntegrationEvent;

class EventSchemaGeneratorTest {

    private static final Path CONTRACTS = Path.of("contracts/events");

    @Test
    void generatedReferenceShapeMatchesCommittedBaseline() {
        assertThatNoException()
                .isThrownBy(
                        () ->
                                EventSchemaGenerator.verify(
                                        CONTRACTS, List.of(ReferenceEvent.class)));
    }

    @Test
    void codeFieldWithoutBaselineUpdateFails() {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> EventSchemaGenerator.verify(CONTRACTS, List.of(DriftedEvent.class)))
                .withMessageContaining("EVENT_SCHEMA_DRIFT")
                .withMessageContaining("extraField");
    }

    @Test
    void commandLineEntryPointLoadsRegisteredEventClass() {
        assertThatNoException()
                .isThrownBy(
                        () ->
                                EventSchemaGenerator.main(
                                        new String[] {
                                            CONTRACTS.toString(), ReferenceEvent.class.getName()
                                        }));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventSchemaGenerator.main(new String[] {CONTRACTS.toString()}))
                .withMessageContaining("Usage");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                EventSchemaGenerator.main(
                                        new String[] {CONTRACTS.toString(), "missing.Event"}))
                .withMessageContaining("Event class not found");
    }

    @Test
    void eventDeclarationMustBeARegisteredIntegrationEventRecord() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventSchemaGenerator.verify(CONTRACTS, List.of(String.class)))
                .withMessageContaining("must be an IntegrationEvent record");
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                EventSchemaGenerator.verify(
                                        CONTRACTS, List.of(UnregisteredEvent.class)))
                .withMessageContaining("has no RegisteredEventContract");
    }

    @Test
    void supportsAllContractComponentShapes(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        String type = "platform.RichEvent.v1";
        Files.writeString(
                directory.resolve(type + ".json"),
                """
                {
                  "required":["eventType","count","ratio","active","tags","nested"],
                  "properties":{
                    "eventType":{"type":"string"},
                    "count":{"type":"integer"},
                    "ratio":{"type":"number"},
                    "active":{"type":"boolean"},
                    "tags":{"type":"array"},
                    "nested":{"type":"object"}
                  }
                }
                """);

        assertThatNoException()
                .isThrownBy(() -> EventSchemaGenerator.verify(directory, List.of(RichEvent.class)));

        Files.writeString(
                directory.resolve(type + ".json"),
                """
                {"required":["eventType","count","ratio","active","tags","nested"],
                 "properties":{"eventType":{"type":"string"}}}
                """);
        assertThatIllegalStateException()
                .isThrownBy(() -> EventSchemaGenerator.verify(directory, List.of(RichEvent.class)))
                .withMessageContaining("missing property count");
    }

    @Test
    void rejectsMissingAndInconsistentBaselines(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> EventSchemaGenerator.verify(directory, List.of(ReferenceEvent.class)))
                .withMessageContaining("Missing registered event schema");

        Path schema = directory.resolve("platform.ReferenceEvent.v1.json");
        String baseline = Files.readString(CONTRACTS.resolve("platform.ReferenceEvent.v1.json"));
        Files.writeString(
                schema, baseline.replace("\"type\": \"integer\"", "\"type\": \"string\""));
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> EventSchemaGenerator.verify(directory, List.of(ReferenceEvent.class)))
                .withMessageContaining("type changed for revision");

        Files.writeString(schema, baseline.replace("\"revision\"\n  ]", "\"referenceId\"\n  ]"));
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> EventSchemaGenerator.verify(directory, List.of(ReferenceEvent.class)))
                .withMessageContaining("record components must be required");
    }

    @RegisteredEventContract("platform.ReferenceEvent.v1")
    private record DriftedEvent(
            String eventType, String referenceId, int revision, String extraField)
            implements IntegrationEvent {}

    private record UnregisteredEvent(String eventType) implements IntegrationEvent {}

    private record Nested(String value) {}

    @RegisteredEventContract("platform.RichEvent.v1")
    private record RichEvent(
            String eventType,
            long count,
            double ratio,
            boolean active,
            List<String> tags,
            Nested nested)
            implements IntegrationEvent {}
}
