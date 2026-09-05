package org.meldtech.platform.shared.infra.web;

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

final class MdcCorrelationIdAccessor implements ThreadLocalAccessor<String> {

    static final String KEY = "correlationId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public @Nullable String getValue() {
        return MDC.get(KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(KEY);
    }
}
