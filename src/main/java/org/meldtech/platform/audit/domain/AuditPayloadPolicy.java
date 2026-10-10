package org.meldtech.platform.audit.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ArrayValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.security.SecretFieldPattern;

public final class AuditPayloadPolicy {

    private static final Set<String> PERMITTED_AUDIT_METADATA_PATHS =
            Set.of("authorization_reference", "signature_reference.key_version");

    private static final Pattern NAMED_ASSIGNMENT =
            Pattern.compile("(?<![\\p{L}\\p{N}_.-])([\\p{L}_][\\p{L}\\p{N}_.-]*)[\"']?\\s*[:=]");
    private static final Pattern EPOCH_REFERENCE =
            Pattern.compile(
                    "(?:"
                            + String.join(
                                    "|",
                                    Arrays.stream(RetentionClass.values()).map(Enum::name).toList())
                            + "):[0-9]{4}-[0-9]{2}-[0-9]{2}");

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
                                if (!PERMITTED_AUDIT_METADATA_PATHS.contains(path)
                                        && SecretFieldPattern.isSecretField(path)) {
                                    throw new SecretAuditFieldException(path);
                                }
                                inspect(child, path);
                            });
        } else if (value instanceof ArrayValue array) {
            for (CanonicalValue child : array.values()) {
                inspect(child, parentPath);
            }
        } else if (value instanceof StringValue text) {
            // Hold events identify partitions as RETENTION_CLASS:YYYY-MM-DD.
            if (EPOCH_REFERENCE.matcher(text.value()).matches()) {
                return;
            }
            // Exception messages and other free text can embed otherwise forbidden fields.
            Matcher assignments = NAMED_ASSIGNMENT.matcher(text.value());
            while (assignments.find()) {
                if (SecretFieldPattern.isSecretField(assignments.group(1))) {
                    throw new SecretAuditFieldException(parentPath);
                }
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
