package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EventTypeTest {

    private final RegisteredEventSchemaValidator validator =
            new RegisteredEventSchemaValidator(Path.of("contracts/events"), new ObjectMapper());

    @Test
    void parsesRegisteredNamingConvention() {
        EventType type = EventType.parse("platform.ReferenceEvent.v1");

        assertThat(type.context()).isEqualTo("platform");
        assertThat(type.name()).isEqualTo("ReferenceEvent");
        assertThat(type.version()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedOrUnregisteredTypes() {
        assertThatIllegalArgumentException().isThrownBy(() -> EventType.parse("platform.bad.v0"));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                validator.validateAndSerialize(
                                        EventType.parse("platform.Missing.v1"),
                                        new ReferenceEvent(
                                                "platform.Missing.v1",
                                                "01950f47-6000-7000-8000-000000000001",
                                                1)));
    }

    @Test
    void validatesPayloadAgainstRegisteredSchema() {
        String json =
                validator.validateAndSerialize(
                        EventType.parse("platform.ReferenceEvent.v1"),
                        new ReferenceEvent(
                                "platform.ReferenceEvent.v1",
                                "01950f47-6000-7000-8000-000000000001",
                                1));

        assertThat(json).contains("platform.ReferenceEvent.v1");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                validator.validateAndSerialize(
                                        EventType.parse("platform.ReferenceEvent.v1"),
                                        new ReferenceEvent(
                                                "platform.ReferenceEvent.v1",
                                                "01950f47-6000-7000-8000-000000000001",
                                                0)));
    }
}
