package org.meldtech.platform.platform.infra.kernel.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record ProblemDefinition(
        String type,
        String title,
        int status,
        String detail,
        Map<String, ExtensionDefinition> extensions) {

    ProblemDefinition {
        extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }
}
