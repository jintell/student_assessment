package org.meldtech.platform.audit.domain;

import java.util.Objects;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;

public record ResolvedRetention(
        RetentionClass retentionClass,
        String policyKey,
        long policyVersion,
        RetentionHorizon horizon) {

    public ResolvedRetention {
        Objects.requireNonNull(retentionClass, "retentionClass");
        Objects.requireNonNull(policyKey, "policyKey");
        Objects.requireNonNull(horizon, "horizon");
    }
}
