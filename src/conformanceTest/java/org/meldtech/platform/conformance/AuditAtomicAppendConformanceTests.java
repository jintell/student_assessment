package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.AuditStatementKind;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.AuditEmitter;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AuditAtomicAppendConformanceTests {

    private static final Set<String> FORBIDDEN_CONNECTION_TYPES =
            Set.of(
                    "io.r2dbc.spi.ConnectionFactory",
                    "org.springframework.r2dbc.core.DatabaseClient",
                    "org.springframework.transaction.reactive.TransactionalOperator",
                    "org.springframework.transaction.reactive.TransactionSynchronizationManager");
    private static final Set<String> FORBIDDEN_ASYNC_ANNOTATIONS =
            Set.of(
                    "org.springframework.scheduling.annotation.Async",
                    "org.springframework.context.event.EventListener",
                    "org.springframework.transaction.event.TransactionalEventListener");

    @Test
    void auditAppendUsesOnlyTheSignedCallerOwnedProtocol() throws ReflectiveOperationException {
        JavaClasses production =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertSignedProtocol(production);
        assertApprovedSqlShapes();
        assertEquals(
                Set.of(AuditStatementKind.LOCK_PREDECESSOR, AuditStatementKind.APPEND_AND_ADVANCE),
                Set.of(AuditStatementKind.values()),
                "The audit finalization state machine may expose only the two approved statements");
    }

    @Test
    void rejectsRequiresNewAsyncAndSeparateConnectionEmission() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.auditappend");

        AssertionError failure =
                assertThrows(AssertionError.class, () -> assertSignedProtocol(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("AUDIT-APPEND-PROTOCOL:"));
        assertTrue(String.valueOf(failure.getMessage()).contains("REQUIRES_NEW"));
        assertTrue(String.valueOf(failure.getMessage()).contains("async/listener"));
        assertTrue(String.valueOf(failure.getMessage()).contains("caller-owned connection"));
    }

    private static void assertSignedProtocol(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        classes.stream()
                .filter(javaClass -> javaClass.isAssignableTo(AuditEmitter.class))
                .filter(javaClass -> !javaClass.isEquivalentTo(AuditEmitter.class))
                .forEach(javaClass -> inspectEmitter(javaClass, violations));
        classes.stream()
                .filter(
                        javaClass ->
                                javaClass
                                        .getName()
                                        .equals(
                                                "org.meldtech.platform.audit.infra.R2dbcAuditAppendRepository"))
                .forEach(javaClass -> inspectAppendRepository(javaClass, violations));

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static void inspectEmitter(JavaClass emitter, List<String> violations) {
        emitter.getMethods().stream()
                .filter(method -> method.isAnnotatedWith(Transactional.class))
                .map(method -> method.getAnnotationOfType(Transactional.class))
                .filter(transactional -> transactional.propagation() == Propagation.REQUIRES_NEW)
                .forEach(
                        ignored ->
                                violations.add(
                                        violation(
                                                emitter,
                                                "REQUIRES_NEW is forbidden; join the caller transaction")));
        emitter.getMethods().stream()
                .filter(AuditAtomicAppendConformanceTests::hasAsyncAnnotation)
                .forEach(
                        method ->
                                violations.add(
                                        violation(
                                                emitter,
                                                "async/listener emission is forbidden on "
                                                        + method.getName())));
        emitter.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName())
                .filter(FORBIDDEN_CONNECTION_TYPES::contains)
                .forEach(
                        type ->
                                violations.add(
                                        violation(
                                                emitter,
                                                "must use the caller-owned connection, not "
                                                        + type)));
    }

    private static boolean hasAsyncAnnotation(JavaMethod method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getRawType().getName())
                .anyMatch(FORBIDDEN_ASYNC_ANNOTATIONS::contains);
    }

    private static void inspectAppendRepository(JavaClass repository, List<String> violations) {
        long approvedCalls =
                repository.getMethodCallsFromSelf().stream()
                        .filter(
                                call ->
                                        call.getTargetOwner()
                                                .isAssignableTo(TransactionalConnection.class))
                        .filter(call -> call.getTarget().getName().equals("createAuditStatement"))
                        .count();
        if (approvedCalls != 2) {
            violations.add(
                    violation(
                            repository,
                            "must execute exactly two approved audit statements, found "
                                    + approvedCalls));
        }
        repository.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTarget().getName().equals("createStatement"))
                .forEach(
                        call ->
                                violations.add(
                                        violation(
                                                repository,
                                                "hidden or non-audit database call bypasses finalization")));
    }

    private static void assertApprovedSqlShapes() throws ReflectiveOperationException {
        Class<?> repository =
                Class.forName("org.meldtech.platform.audit.infra.R2dbcAuditAppendRepository");
        String lockSql = staticString(repository, "LOCK_HEAD_SQL");
        String appendSql = staticString(repository, "APPEND_SQL");

        assertTrue(lockSql.contains("SELECT seq, head_hash, hash_algo_version"));
        assertTrue(lockSql.contains("FOR UPDATE"));
        assertTrue(!lockSql.contains("INSERT"), "Lazy chain-head creation is forbidden");
        assertTrue(appendSql.contains("INSERT INTO audit.audit_event"));
        assertTrue(appendSql.contains("UPDATE audit.audit_chain_head"));
        assertTrue(
                !appendSql.contains("INSERT INTO audit.audit_chain_head"),
                "Lazy chain-head creation is forbidden");
    }

    private static String staticString(Class<?> owner, String fieldName)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static String violation(JavaClass owner, String detail) {
        return "AUDIT-APPEND-PROTOCOL: " + owner.getName() + " " + detail;
    }
}
