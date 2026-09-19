package org.meldtech.migrationverify.measure;

public record ObservedRelationLock(
        String relation, String lockMode, String transactionIdentity, boolean granted) {}
