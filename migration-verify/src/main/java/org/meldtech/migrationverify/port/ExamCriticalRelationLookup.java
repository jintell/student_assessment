package org.meldtech.migrationverify.port;

@FunctionalInterface
public interface ExamCriticalRelationLookup {

    boolean isCritical(String relation);
}
