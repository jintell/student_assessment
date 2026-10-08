package org.meldtech.platform.audit.domain;

import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;

public final class AuditPayloadPolicy {

    private AuditPayloadPolicy() {}

    public static void requireSecretFree(ObjectValue payload) {
        inspect(payload, "");
    }

    private static void inspect(CanonicalValue value, String parentPath) {
        if (value instanceof ObjectValue object) {
            object.members()
                    .forEach(
                            (field, child) -> {
                                String path =
                                        parentPath.isEmpty() ? field : parentPath + "." + field;
                                if (SecretFieldPattern.isSecretField(path)) {
                                    throw new SecretAuditFieldException(path);
                                }
                                inspect(child, path);
                            });
        } else if (value instanceof ArrayValue array) {
            for (CanonicalValue child : array.values()) {
                inspect(child, parentPath);
            }
        }
    }

    public static final class SecretAuditFieldException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private SecretAuditFieldException(String fieldPath) {
            super("Audit payload contains a forbidden secret field: " + fieldPath);
        }
    }
}
