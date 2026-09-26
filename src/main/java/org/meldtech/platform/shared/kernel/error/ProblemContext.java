package org.meldtech.platform.shared.kernel.error;

import java.net.URI;
import java.util.Objects;
import org.meldtech.platform.shared.kernel.context.CorrelationId;

public record ProblemContext(URI instance, CorrelationId correlationId) {

    public ProblemContext {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(correlationId, "correlationId");
        if (instance.isAbsolute() || !instance.getPath().startsWith("/")) {
            throw new IllegalArgumentException(
                    "Problem instance must be a normalized request path");
        }
    }
}
