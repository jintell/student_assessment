package org.meldtech.platform.shared.kernel.idempotency;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class StoredResponse {

    public static final int MAX_BODY_BYTES = 1_048_576;
    private static final Set<String> PERMITTED_HEADERS = Set.of("Location", "Retry-After");

    private final int status;
    private final Map<String, String> headers;
    private final String contentType;
    private final byte[] body;

    public StoredResponse(
            int status, Map<String, String> headers, String contentType, byte[] body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP status code");
        }
        this.status = status;
        this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        if (!PERMITTED_HEADERS.containsAll(this.headers.keySet())) {
            throw new IllegalArgumentException("StoredResponse contains a non-replayable header");
        }
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("StoredResponse body exceeds the replay limit");
        }
        this.body = body.clone();
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] body() {
        return body.clone();
    }
}
