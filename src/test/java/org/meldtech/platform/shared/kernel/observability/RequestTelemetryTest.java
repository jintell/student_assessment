package org.meldtech.platform.shared.kernel.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RequestTelemetryTest {

    @Test
    void metadataCreatesTheApprovedSpanName() {
        RequestTelemetry.RequestMetadata metadata =
                new RequestTelemetry.RequestMetadata(
                        "platform",
                        "getConformanceReference",
                        RequestTelemetry.Audience.OPERATOR,
                        RequestTelemetry.Operation.READ,
                        RequestTelemetry.RouteClass.STANDARD);

        assertEquals("platform.getConformanceReference", metadata.spanName());
    }

    @Test
    void metadataRejectsUnboundedNames() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RequestTelemetry.RequestMetadata(
                                "Platform",
                                "getConformanceReference",
                                RequestTelemetry.Audience.OPERATOR,
                                RequestTelemetry.Operation.READ,
                                RequestTelemetry.RouteClass.STANDARD));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RequestTelemetry.RequestMetadata(
                                "platform",
                                "get-reference",
                                RequestTelemetry.Audience.OPERATOR,
                                RequestTelemetry.Operation.READ,
                                RequestTelemetry.RouteClass.STANDARD));
    }
}
