package org.meldtech.platform.platform.infra.observability;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class StructuredJsonLogEncoder {

    private final ObjectMapper objectMapper;
    private final RedactingJsonSerializer redactor;

    StructuredJsonLogEncoder() {
        this(new ObjectMapper(), new RedactingJsonSerializer());
    }

    StructuredJsonLogEncoder(ObjectMapper objectMapper, RedactingJsonSerializer redactor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    byte[] encode(StructuredLogEvent event) {
        Objects.requireNonNull(event, "event");
        ObjectNode root = objectMapper.createObjectNode();
        put(root, "timestamp", () -> objectMapper.valueToTree(event.timestamp().toString()));
        put(root, "level", () -> objectMapper.valueToTree(event.level().name()));
        put(root, "logger", () -> objectMapper.valueToTree(event.logger()));
        put(root, "message", () -> objectMapper.valueToTree(event.message()));
        put(
                root,
                "correlationId",
                () -> objectMapper.valueToTree(event.correlationId().toString()));
        put(root, "traceId", () -> objectMapper.valueToTree(event.traceId()));
        put(root, "spanId", () -> objectMapper.valueToTree(event.spanId()));
        put(root, "role", () -> objectMapper.valueToTree(event.role().name()));
        put(root, "module", () -> objectMapper.valueToTree(event.module()));
        put(root, "slice", () -> objectMapper.valueToTree(event.slice()));
        event.actorType()
                .ifPresent(
                        value ->
                                put(
                                        root,
                                        "actorType",
                                        () -> objectMapper.valueToTree(value.name())));
        event.actorId()
                .ifPresent(
                        value ->
                                put(
                                        root,
                                        "actorId",
                                        () -> objectMapper.valueToTree(value.toString())));
        event.tenantId()
                .ifPresent(
                        value ->
                                put(
                                        root,
                                        "tenantId",
                                        () -> objectMapper.valueToTree(value.toString())));
        event.eventCode()
                .ifPresent(
                        value ->
                                put(
                                        root,
                                        "eventCode",
                                        () -> objectMapper.valueToTree(value.name())));
        event.errorCode()
                .ifPresent(value -> put(root, "errorCode", () -> objectMapper.valueToTree(value)));
        event.durationMs()
                .ifPresent(value -> put(root, "durationMs", () -> objectMapper.valueToTree(value)));
        event.dbQueryCount()
                .ifPresent(
                        value -> put(root, "dbQueryCount", () -> objectMapper.valueToTree(value)));
        event.error().ifPresent(value -> put(root, "error", () -> error(value)));

        try {
            return (objectMapper.writeValueAsString(root) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Structured log event could not be encoded");
        }
    }

    private ObjectNode error(StructuredLogEvent.StructuredError error) {
        ObjectNode value = objectMapper.createObjectNode();
        ArrayNode stack = value.putArray("stack");
        for (StructuredLogEvent.StackFrame frame : error.stack()) {
            ObjectNode frameNode = stack.addObject();
            put(
                    frameNode,
                    "declaringClass",
                    () -> objectMapper.valueToTree(frame.declaringClass()));
            put(frameNode, "method", () -> objectMapper.valueToTree(frame.method()));
            put(frameNode, "file", () -> objectMapper.valueToTree(frame.file()));
            put(frameNode, "line", () -> objectMapper.valueToTree(frame.line()));
        }
        put(value, "truncated", () -> objectMapper.valueToTree(error.truncated()));
        return value;
    }

    private void put(ObjectNode target, String fieldPath, Supplier<? extends JsonNode> value) {
        target.set(fieldPath, redactor.serialize(fieldPath, value));
    }
}
