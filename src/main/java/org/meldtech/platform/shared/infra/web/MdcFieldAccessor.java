package org.meldtech.platform.shared.infra.web;

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

final class MdcFieldAccessor implements ThreadLocalAccessor<String> {

    private final String key;

    MdcFieldAccessor(String key) {
        this.key = key;
    }

    @Override
    public Object key() {
        return key;
    }

    @Override
    public @Nullable String getValue() {
        return MDC.get(key);
    }

    @Override
    public void setValue(String value) {
        MDC.put(key, value);
    }

    @Override
    public void setValue() {
        MDC.remove(key);
    }
}
