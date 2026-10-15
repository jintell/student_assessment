package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;

class SweepLogPolicyTest {

    @Test
    void everySweepTelemetryFieldUsesTheSharedSecretPolicy() {
        assertThat(SweepLogPolicy.publicFields())
                .containsExactlyInAnyOrder("sweep", "sweep_name", "outcome", "action")
                .allMatch(field -> !SecretFieldPattern.isSecretField(field));
    }

    @Test
    void unsafeSweepNamesCannotReachLogs() {
        assertThat(SweepLogPolicy.safeSweepName("reference-sweep")).isEqualTo("reference-sweep");
        assertThat(SweepLogPolicy.safeSweepName("tenant=abc\npassword=driver-secret"))
                .isEqualTo("invalid");
    }
}
