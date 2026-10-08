package org.meldtech.platform.audit.slice.getComplianceAuditEvents;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.reactive.function.server.ServerRequest;

public record Request(
        Optional<Instant> occurredFrom,
        Optional<Instant> occurredTo,
        Optional<String> entityType,
        Optional<String> entityId,
        Optional<String> eventType,
        Optional<String> cursor,
        int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;
    public static final Duration MAX_TIME_RANGE = Duration.ofDays(366);
    private static final Pattern ENTITY_TYPE =
            Pattern.compile("[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*)*");
    private static final Pattern EVENT_TYPE =
            Pattern.compile("[a-z][a-z0-9]*[.][A-Z][A-Z0-9_]*[.]v[1-9][0-9]*");

    public Request {
        Objects.requireNonNull(occurredFrom, "occurredFrom");
        Objects.requireNonNull(occurredTo, "occurredTo");
        text(entityType, "entityType");
        text(entityId, "entityId");
        text(eventType, "eventType");
        text(cursor, "cursor");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and 500");
        }
        if (entityType.isPresent() != entityId.isPresent()) {
            throw new IllegalArgumentException("entityType and entityId must be supplied together");
        }
        if (entityType.isPresent() && eventType.isPresent()) {
            throw new IllegalArgumentException("query modes are mutually exclusive");
        }
        entityType.ifPresent(
                value -> {
                    if (!ENTITY_TYPE.matcher(value).matches()) {
                        throw new IllegalArgumentException("entityType is not registered format");
                    }
                });
        eventType.ifPresent(
                value -> {
                    if (!EVENT_TYPE.matcher(value).matches()) {
                        throw new IllegalArgumentException("eventType is not registered format");
                    }
                });
        if (occurredFrom.isPresent() && occurredTo.isPresent()) {
            Instant from = occurredFrom.orElseThrow();
            Instant to = occurredTo.orElseThrow();
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_TIME_RANGE) > 0) {
                throw new IllegalArgumentException("occurrence range is inverted or too large");
            }
        }
    }

    public static Request from(ServerRequest request) {
        return new Request(
                instant(request.queryParam("occurredFrom")),
                instant(request.queryParam("occurredTo")),
                request.queryParam("entityType"),
                request.queryParam("entityId"),
                request.queryParam("eventType"),
                request.queryParam("cursor"),
                request.queryParam("pageSize").map(Integer::parseInt).orElse(DEFAULT_PAGE_SIZE));
    }

    public QueryMode mode() {
        if (entityType.isPresent()) {
            return QueryMode.ENTITY_HISTORY;
        }
        if (eventType.isPresent()) {
            return QueryMode.EVENT_TYPE_TIMELINE;
        }
        return QueryMode.TENANT_TIMELINE;
    }

    public String filterFingerprint() {
        String normalized =
                String.join(
                        "\n",
                        mode().name(),
                        occurredFrom.map(Instant::toString).orElse(""),
                        occurredTo.map(Instant::toString).orElse(""),
                        entityType.orElse(""),
                        entityId.orElse(""),
                        eventType.orElse(""));
        return HexFormat.of()
                .formatHex(
                        org.meldtech.platform.audit.domain.AuditHashing.sha256()
                                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<Instant> instant(Optional<String> value) {
        return value.map(Instant::parse);
    }

    private static void text(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(
                present -> {
                    if (present.isBlank()) {
                        throw new IllegalArgumentException(name + " must not be blank");
                    }
                });
    }

    public enum QueryMode {
        TENANT_TIMELINE,
        ENTITY_HISTORY,
        EVENT_TYPE_TIMELINE
    }
}
