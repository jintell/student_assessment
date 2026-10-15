package org.meldtech.platform.platform.infra.persistence;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.util.Objects;
import java.util.function.Function;
import org.meldtech.platform.platform.api.RoleScoped;
import org.meldtech.platform.platform.api.SweepConnection;
import org.meldtech.platform.platform.api.SweepDataModule;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.api.SweepTransactions;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.platform.infra.observability.TracingConnectionFactory;
import org.meldtech.platform.shared.api.ActorContextPropagation;
import org.meldtech.platform.shared.api.PlatformOperation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

@Component
@RoleScoped("worker")
final class DefaultSweepTransactions implements SweepTransactions {

    private final ConnectionFactory connections;
    private final ActorContextPropagation contexts;

    DefaultSweepTransactions(
            @Qualifier("workerConnectionFactory") ConnectionFactory connections,
            ActorContextPropagation contexts) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        ConnectionFactory secured =
                connections instanceof TracingConnectionFactory tracing
                        ? tracing.delegate()
                        : connections;
        if (!(secured instanceof SecurityContextInitializer)) {
            throw new IllegalArgumentException(
                    "Sweep transactions require a security-initialized factory");
        }
    }

    @Override
    public <T> Mono<T> inPlatformTransaction(
            SweepDataModule module, Function<SweepConnection, Mono<T>> work) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(work, "work");
        return Mono.deferContextual(
                context -> {
                    ActorContext actor = requireSweepActor(context);
                    requireNoTransaction(context);
                    requireDeclaredModule(context, module);
                    PlatformOperation operation = operationFor(module, actor);
                    Mono<T> transaction =
                            SecurityContextInitializer.managedTransaction(
                                    connections, connection -> invoke(connection, work));
                    return SecurityContextInitializer.withPlatformScope(
                            roleFor(module), operation, actor, transaction);
                });
    }

    @Override
    public <T> Mono<T> inTenantTransaction(
            SweepDataModule module, TenantId tenantId, Function<SweepConnection, Mono<T>> work) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(work, "work");
        return Mono.deferContextual(
                context -> {
                    ActorContext platformActor = requireSweepActor(context);
                    requireNoTransaction(context);
                    requireDeclaredModule(context, module);
                    operationFor(module, platformActor);
                    ActorContext tenantActor =
                            ActorContext.tenantSystem(
                                    platformActor.systemActorName().orElseThrow(),
                                    tenantId,
                                    platformActor.correlationId(),
                                    platformActor.sourceIp());
                    Mono<T> transaction =
                            SecurityContextInitializer.managedTransaction(
                                    connections, connection -> invoke(connection, work));
                    return SecurityContextInitializer.withTenantScope(
                                    roleFor(module), tenantId, transaction)
                            .contextWrite(updated -> contexts.write(updated, tenantActor));
                });
    }

    static ActorContext requireSweepActor(ContextView context) {
        if (!context.hasKey(SweepMetadata.class) || !context.hasKey(ActorContext.class)) {
            throw new SecurityException("Sweep execution context is required");
        }
        SweepMetadata declaration = context.get(SweepMetadata.class);
        ActorContext actor = context.get(ActorContext.class);
        if (actor.tenantId().isPresent()
                || actor.systemActorName().filter(declaration.systemActor()::equals).isEmpty()) {
            throw new SecurityException("Sweep actor does not match its declaration");
        }
        return actor;
    }

    private static void requireNoTransaction(ContextView context) {
        if (context.hasKey(SweepConnection.class)
                || context.hasKey(TransactionalConnection.class)) {
            throw new IllegalStateException("Nested sweep transactions are forbidden");
        }
    }

    private static void requireDeclaredModule(ContextView context, SweepDataModule module) {
        SweepMetadata declaration = context.get(SweepMetadata.class);
        boolean retentionCoordinator =
                declaration.name().equals("retention")
                        && declaration.owningModule().equals("audit")
                        && declaration.owningFeature().equals("FEAT-PRIV-001")
                        && declaration.systemActor()
                                == org.meldtech.platform.shared.kernel.context.SystemActor
                                        .RETENTION_ENGINE;
        if (!retentionCoordinator && !declaration.owningModule().equals(module.moduleName())) {
            throw new SecurityException("Sweep transaction must target its declared module");
        }
    }

    private static PlatformOperation operationFor(SweepDataModule module, ActorContext actor) {
        return switch (actor.systemActorName().orElseThrow()) {
            case AUTO_SUBMIT_SWEEPER ->
                    requireModule(
                            module, SweepDataModule.DELIVERY, PlatformOperation.AUTO_SUBMIT_SWEEP);
            case GRADING_WORKER ->
                    requireModule(
                            module, SweepDataModule.GRADING, PlatformOperation.STALE_CLAIM_RECLAIM);
            case IDP_RECONCILER ->
                    requireModule(module, SweepDataModule.IAM, PlatformOperation.RECONCILIATION);
            case OUTBOX_RELAY ->
                    requireModule(module, SweepDataModule.OUTBOX, PlatformOperation.OUTBOX_RELAY);
            case RETENTION_ENGINE -> {
                if (module == SweepDataModule.OUTBOX) {
                    throw new SecurityException("Retention cannot assume the relay role");
                }
                yield PlatformOperation.RETENTION_SWEEP;
            }
            case NOTIFICATION_DISPATCHER ->
                    throw new SecurityException("Actor has no sweep transaction scope");
        };
    }

    private static PlatformOperation requireModule(
            SweepDataModule actual, SweepDataModule required, PlatformOperation operation) {
        if (actual != required) {
            throw new SecurityException("Sweep actor cannot assume the requested module role");
        }
        return operation;
    }

    private static AssumableDatabaseRole roleFor(SweepDataModule module) {
        return switch (module) {
            case TENANCY -> AssumableDatabaseRole.TENANCY;
            case IAM -> AssumableDatabaseRole.IAM;
            case ACADEMIC -> AssumableDatabaseRole.ACADEMIC;
            case PEOPLE -> AssumableDatabaseRole.PEOPLE;
            case QUESTION_BANK -> AssumableDatabaseRole.QUESTION_BANK;
            case AUTHORING -> AssumableDatabaseRole.AUTHORING;
            case EXAM_ACCESS -> AssumableDatabaseRole.EXAM_ACCESS;
            case DELIVERY -> AssumableDatabaseRole.DELIVERY;
            case GRADING -> AssumableDatabaseRole.GRADING;
            case RESULT -> AssumableDatabaseRole.RESULT;
            case CORRECTION -> AssumableDatabaseRole.CORRECTION;
            case NOTIFICATION -> AssumableDatabaseRole.NOTIFICATION;
            case OUTBOX -> AssumableDatabaseRole.OUTBOX_RELAY;
        };
    }

    private static <T> Mono<T> invoke(
            Connection connection, Function<SweepConnection, Mono<T>> work) {
        SweepConnection handle = connection::createStatement;
        return Mono.defer(() -> work.apply(handle))
                .contextWrite(context -> context.put(SweepConnection.class, handle));
    }
}
