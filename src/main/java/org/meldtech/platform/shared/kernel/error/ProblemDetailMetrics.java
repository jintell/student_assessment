package org.meldtech.platform.shared.kernel.error;

public interface ProblemDetailMetrics {

    ProblemDetailMetrics NOOP =
            new ProblemDetailMetrics() {
                @Override
                public void emitted(String code) {}

                @Override
                public void fallback(FallbackReason reason) {}
            };

    void emitted(String code);

    void fallback(FallbackReason reason);

    enum FallbackReason {
        CATALOGUE_MISS,
        SERIALIZATION_FAILURE,
        MISSING_CORRELATION
    }
}
