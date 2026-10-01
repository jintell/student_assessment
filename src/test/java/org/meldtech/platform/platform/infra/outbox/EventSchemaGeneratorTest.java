package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

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

    @RegisteredEventContract("platform.ReferenceEvent.v1")
    private record DriftedEvent(
            String eventType, String referenceId, int revision, String extraField)
            implements IntegrationEvent {}
}
