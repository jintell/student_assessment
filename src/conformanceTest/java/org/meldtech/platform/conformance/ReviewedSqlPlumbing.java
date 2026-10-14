package org.meldtech.platform.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reviewed forwarding and closed SQL builders; edits require renewed inspection, not a bypass. */
final class ReviewedSqlPlumbing {
    private static final String BASE = "org.meldtech.platform.";
    private static final Map<String, String> SOURCES =
            Map.of(
                    BASE + "audit.infra.R2dbcAuditDispositionOperations",
                    "f1c78ce02c35702b0134c22a98fc8aa7799959d480bec73a23ff1bc63fe85337",
                    BASE + "audit.slice.getComplianceAuditEvents.infra.R2dbcComplianceAuditQueries",
                    "8f90145469ffd2b9d00457e1b440c2eab7ee25b77c153d8fc769917707485b3d",
                    BASE + "platform.api.TransactionalConnection",
                    "a9f8c497d3005a675bdc7c61aa6aa8ddd7119fe01d3c93a268c9fe50eba3a880",
                    BASE + "platform.infra.observability.TracingConnectionFactory",
                    "150753188141eddc71a05dc91c6a6c9a0d737631087084210cdb8f29aea68e7e",
                    BASE + "platform.infra.outbox.ProcessedEventGuard",
                    "8567084b1ac6d4f65c5cb728665c73f461dd415707edf74bc87268c5c9458b85",
                    BASE + "platform.infra.persistence.DefaultTransactionalCollaboration",
                    "7dae6d52a67aaac3c458cce902fbac2a4c707ad896f82578615e1d5e9a9a4d5c",
                    BASE + "platform.infra.persistence.SecurityContextInitializer",
                    "12d5074f9c0251f874bab50000e0ef3ea0c27811801fbdeeee6db6c92821c641",
                    BASE + "platform.infra.persistence.DefaultSweepTransactions",
                    "60d32141a822d484c599bd24c63dbe569fc89c88bac6c250929d86ae004eadb4");

    private ReviewedSqlPlumbing() {}

    static boolean unchanged(String className) {
        String owner = className.split("\\$", 2)[0];
        String expected = SOURCES.get(owner);
        if (expected == null) {
            return false;
        }
        try {
            String source =
                    Files.readString(Path.of("src/main/java", owner.replace('.', '/') + ".java"));
            return expected.equals(RuntimeSqlResources.digest(source));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect SQL plumbing " + owner, failure);
        }
    }
}
