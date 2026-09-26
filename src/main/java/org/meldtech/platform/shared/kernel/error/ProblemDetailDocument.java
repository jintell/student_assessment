package org.meldtech.platform.shared.kernel.error;

import java.net.URI;
import java.util.Map;
import org.meldtech.platform.shared.kernel.context.CorrelationId;

public final class ProblemDetailDocument {

    private final URI type;
    private final String title;
    private final int status;
    private final String code;
    private final String detail;
    private final URI instance;
    private final CorrelationId correlationId;
    private final Map<String, Object> extensions;

    ProblemDetailDocument(
            URI type,
            String title,
            int status,
            String code,
            String detail,
            URI instance,
            CorrelationId correlationId,
            Map<String, Object> extensions) {
        this.type = type;
        this.title = title;
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.instance = instance;
        this.correlationId = correlationId;
        this.extensions = Map.copyOf(extensions);
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public URI instance() {
        return instance;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }
}
