package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.CrossModuleCommandApi;
import org.meldtech.platform.shared.api.PolicyProtectedRoute;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorType;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.outbox.OutboxWriter;
import org.springframework.http.HttpMethod;

class ActorAttributionConformanceTests {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String ACTOR_CONTEXT = ActorContext.class.getName();

    @Test
    void everyWritePathRequiresActorContext() {
        assertEveryWritePathRequiresActorContext(productionClasses());
    }

    @Test
    void rejectsANoArgumentWritePath() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.conformance.fixtures.actor.noarg");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertEveryWritePathRequiresActorContext(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("ACTOR-CONTEXT:"));
        assertTrue(String.valueOf(failure.getMessage()).contains("no direct ActorContext"));
    }

    private static void assertEveryWritePathRequiresActorContext(JavaClasses classes) {
        List<String> violations = new ArrayList<>();

        classes.stream()
                .filter(ActorAttributionConformanceTests::isRoute)
                .forEach(route -> inspectRoute(route, classes, violations));

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    @Test
    void systemActorsCanOnlyBeConstructedThroughTheClosedEnumeration() {
        assertSystemActorsCanOnlyBeConstructedThroughTheClosedEnumeration(productionClasses());
    }

    @Test
    void rejectsAFreeTextSystemActorName() {
        JavaClasses fixture =
                new ClassFileImporter()
                        .importPackages(
                                "org.meldtech.platform.conformance.fixtures.actor.freetext");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () ->
                                assertSystemActorsCanOnlyBeConstructedThroughTheClosedEnumeration(
                                        fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("SYSTEM-ACTOR:"));
        assertTrue(String.valueOf(failure.getMessage()).contains("free text"));
    }

    private static void assertSystemActorsCanOnlyBeConstructedThroughTheClosedEnumeration(
            JavaClasses classes) {
        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (javaClass.isEquivalentTo(ActorContext.class)
                    || javaClass.isEquivalentTo(ActorType.class)) {
                continue;
            }
            javaClass.getFieldAccessesFromSelf().stream()
                    .filter(ActorAttributionConformanceTests::accessesSystemActorType)
                    .forEach(
                            access ->
                                    violations.add(
                                            systemActorViolation(
                                                    javaClass,
                                                    "references ActorType.SYSTEM directly")));
            javaClass.getMethodCallsFromSelf().stream()
                    .filter(ActorAttributionConformanceTests::parsesSystemActorFromText)
                    .forEach(
                            call ->
                                    violations.add(
                                            systemActorViolation(
                                                    javaClass,
                                                    "constructs a SystemActor from free text")));
            javaClass.getConstructorCallsFromSelf().stream()
                    .filter(call -> call.getTargetOwner().isEquivalentTo(ActorContext.class))
                    .forEach(
                            call ->
                                    violations.add(
                                            systemActorViolation(
                                                    javaClass,
                                                    "bypasses the named ActorContext factories")));
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private static void inspectRoute(
            JavaClass route, JavaClasses classes, List<String> violations) {
        List<String> methods =
                route.getMethod("descriptor").getFieldAccesses().stream()
                        .filter(access -> access.getTargetOwner().isEquivalentTo(HttpMethod.class))
                        .map(access -> access.getTarget().getName())
                        .toList();
        if (methods.size() != 1) {
            violations.add(
                    actorContextViolation(
                            route.getName(),
                            "route descriptor has no single classifiable HTTP method"));
            return;
        }
        if (!WRITE_METHODS.contains(methods.getFirst())) {
            return;
        }

        List<JavaClass> handlers =
                classes.stream()
                        .filter(
                                candidate ->
                                        candidate
                                                .getName()
                                                .equals(route.getPackageName() + ".Handler"))
                        .toList();
        if (handlers.size() != 1) {
            violations.add(
                    actorContextViolation(
                            route.getName(), "write route has no single colocated Handler"));
            return;
        }

        JavaClass handler = handlers.getFirst();
        List<JavaMethod> entryMethods =
                handler.getMethods().stream()
                        .filter(method -> method.getName().equals("handle"))
                        .toList();
        if (entryMethods.size() != 1) {
            violations.add(
                    actorContextViolation(
                            handler.getName(), "write handler has no single handle entry method"));
            return;
        }
        requireActorContext(handler.getName() + ".handle", entryMethods.getFirst(), violations);

        handler.getMethodCallsFromSelf().stream()
                .filter(ActorAttributionConformanceTests::targetsWritePort)
                .forEach(
                        call ->
                                requireActorContext(
                                        call.getTarget().getFullName(), call, violations));
    }

    private static void requireActorContext(
            String location, JavaMethod method, List<String> violations) {
        if (method.getRawParameterTypes().stream()
                .noneMatch(type -> type.getName().equals(ACTOR_CONTEXT))) {
            violations.add(actorContextViolation(location, "has no direct ActorContext parameter"));
        }
    }

    private static void requireActorContext(
            String location, JavaMethodCall call, List<String> violations) {
        if (call.getTarget().getRawParameterTypes().stream()
                .noneMatch(type -> type.getName().equals(ACTOR_CONTEXT))) {
            violations.add(actorContextViolation(location, "has no direct ActorContext parameter"));
        }
    }

    private static boolean isRoute(JavaClass javaClass) {
        return !javaClass.isInterface() && javaClass.isAssignableTo(PolicyProtectedRoute.class);
    }

    private static boolean targetsWritePort(JavaMethodCall call) {
        JavaClass owner = call.getTargetOwner();
        if (!owner.isInterface() || owner.isAssignableTo(TenantScopedQuery.class)) {
            return false;
        }
        return owner.isAssignableTo(CrossModuleCommandApi.class)
                || owner.isAssignableTo(OutboxWriter.class)
                || owner.getSimpleName().equals("Commands")
                || owner.getSimpleName().endsWith("Writer")
                || owner.getSimpleName().endsWith("Emitter")
                || owner.getSimpleName().endsWith("Repository");
    }

    private static boolean accessesSystemActorType(JavaFieldAccess access) {
        return access.getTargetOwner().isEquivalentTo(ActorType.class)
                && access.getTarget().getName().equals("SYSTEM");
    }

    private static boolean parsesSystemActorFromText(JavaMethodCall call) {
        return call.getTargetOwner().isEquivalentTo(SystemActor.class)
                && call.getTarget().getName().equals("valueOf");
    }

    private static String actorContextViolation(String location, String detail) {
        return "ACTOR-CONTEXT: write path " + location + " " + detail + ".";
    }

    private static String systemActorViolation(JavaClass origin, String detail) {
        return "SYSTEM-ACTOR: " + origin.getName() + " " + detail + ".";
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));
    }
}
