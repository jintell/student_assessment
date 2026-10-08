package org.meldtech.platform.audit.infra;

record KmsKeyMetadata(
        boolean enabled,
        boolean asymmetric,
        boolean nonExportable,
        boolean auditSealerSignOnly,
        String environment,
        String algorithm) {}
