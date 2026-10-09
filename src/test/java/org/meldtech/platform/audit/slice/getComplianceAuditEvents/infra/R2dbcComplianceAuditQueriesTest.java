package org.meldtech.platform.audit.slice.getComplianceAuditEvents.infra;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.ComplianceCursor;
import org.meldtech.platform.audit.slice.getComplianceAuditEvents.Request;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.test.StepVerifier;

class R2dbcComplianceAuditQueriesTest {

    private static final TenantId TENANT = TenantId.parse("00000000-0000-0000-0000-000000000071");
    private static final Instant AS_OF = Instant.parse("2026-04-01T00:00:00Z");
    private static final Request REQUEST =
            new Request(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    100);
    private final R2dbcComplianceAuditQueries queries = new R2dbcComplianceAuditQueries();

    @Test
    void refusesToAcquireAnIndependentConnection() {
        StepVerifier.create(queries.find(TENANT, REQUEST, AS_OF, Optional.empty(), 101))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 502})
    void rejectsUnboundedPageLimits(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> queries.find(TENANT, REQUEST, AS_OF, Optional.empty(), limit));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "filter", "snapshot"})
    void rejectsCursorFromAnotherQuery(String changed) {
        var cursor =
                new ComplianceCursor(
                        1,
                        changed.equals("tenant")
                                ? "00000000-0000-0000-0000-000000000072"
                                : TENANT.toString(),
                        changed.equals("filter") ? "different" : REQUEST.filterFingerprint(),
                        changed.equals("snapshot") ? AS_OF.minusSeconds(1) : AS_OF,
                        AS_OF.minusSeconds(30),
                        "GENERAL_AUDIT_EVENT",
                        0,
                        1);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> queries.find(TENANT, REQUEST, AS_OF, Optional.of(cursor), 101));
    }
}
