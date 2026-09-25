package org.meldtech.platform.platform.infra.kernel.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record ErrorCatalogue(int version, Map<String, ProblemDefinition> problems) {

    ErrorCatalogue {
        problems = Collections.unmodifiableMap(new LinkedHashMap<>(problems));
    }
}
