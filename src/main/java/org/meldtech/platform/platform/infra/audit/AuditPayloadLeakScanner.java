package org.meldtech.platform.platform.infra.audit;

import java.nio.file.Path;
import org.meldtech.platform.platform.infra.security.CapturedJsonPayloadLeakScanner;

public final class AuditPayloadLeakScanner {

    private AuditPayloadLeakScanner() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: AuditPayloadLeakScanner <payload-capture-directory>");
        }
        scan(Path.of(arguments[0]));
    }

    static void scan(Path captureDirectory) {
        CapturedJsonPayloadLeakScanner.scan(captureDirectory, "AUDIT_PAYLOAD");
    }
}
