package org.meldtech.platform.platform.infra.kernel.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record ExtensionDefinition(String type, boolean required, Map<String, Object> constraints) {

    ExtensionDefinition {
        constraints = Collections.unmodifiableMap(new LinkedHashMap<>(constraints));
    }
}
