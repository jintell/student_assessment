package org.meldtech.platform.platform.infra.runtime;

import java.util.Set;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;

final class SweepLogPolicy {

    static final String SWEEP = publicField("sweep");
    static final String SWEEP_NAME = publicField("sweep_name");
    static final String OUTCOME = publicField("outcome");
    static final String ACTION = publicField("action");

    private static final Set<String> PUBLIC_FIELDS = Set.of(SWEEP, SWEEP_NAME, OUTCOME, ACTION);
    private static final Pattern SWEEP_VALUE = Pattern.compile("[a-z][a-z0-9-]{0,95}");

    private SweepLogPolicy() {}

    static Set<String> publicFields() {
        return PUBLIC_FIELDS;
    }

    static String safeSweepName(String sweepName) {
        return sweepName != null && SWEEP_VALUE.matcher(sweepName).matches()
                ? sweepName
                : "invalid";
    }

    private static String publicField(String field) {
        if (SecretFieldPattern.isSecretField(field)) {
            throw new IllegalArgumentException("Secret field is forbidden in sweep telemetry");
        }
        return field;
    }
}
