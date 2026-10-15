package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.api.SweepConnection;
import org.meldtech.platform.platform.api.SweepDataModule;
import org.meldtech.platform.platform.api.SweepMetadata;
import org.meldtech.platform.platform.infra.runtime.TransactionCleanup;
import org.meldtech.platform.shared.api.ActorContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.context.SystemActor;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class DefaultSweepTransactionsTest {

    private static final TenantId TENANT = TenantId.parse("10000000-0000-0000-0000-000000000001");
    private static final ActorContextPropagation CONTEXTS =
            (context, actor) -> context.put(ActorContext.class, actor);

    @Test
    void cancelledTransactionKeepsCleanupPendingUntilRollbackAndCloseFinish() {
        var events = new ArrayList<String>();
        var rollback = Sinks.<Void>empty();
        var close = Sinks.<Void>empty();
        Connection connection = mock(Connection.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        doReturn(Mono.just(connection)).when(factory).create();
        doReturn(Mono.empty()).when(connection).beginTransaction();
        doReturn(rollback.asMono().doOnSuccess(ignored -> events.add("rollback")))
                .when(connection)
                .rollbackTransaction();
        doReturn(close.asMono().doOnSuccess(ignored -> events.add("close")))
                .when(connection)
                .close();
        var cleanup = new TransactionCleanup();
        var transaction =
                SecurityContextInitializer.managedTransaction(factory, ignored -> Mono.never())
                        .contextWrite(context -> context.put(TransactionCleanup.class, cleanup))
                        .subscribe();

        transaction.dispose();
        StepVerifier.create(cleanup.await())
                .then(() -> assertThat(events).isEmpty())
                .then(() -> rollback.tryEmitEmpty())
                .then(() -> assertThat(events).containsExactly("rollback"))
                .then(() -> close.tryEmitEmpty())
                .verifyComplete();
        assertThat(events).containsExactly("rollback", "close");
        verify(connection, never()).commitTransaction();
    }

    @Test
    void installsTheClosedRoleAndMarkerBeforeBusinessSql() {
        List<Scope> scopes =
                List.of(
                        new Scope(
                                SweepDataModule.DELIVERY,
                                SystemActor.AUTO_SUBMIT_SWEEPER,
                                "auto_submit_sweep",
                                "app_delivery"),
                        new Scope(
                                SweepDataModule.GRADING,
                                SystemActor.GRADING_WORKER,
                                "stale_claim_reclaim",
                                "app_grading"),
                        new Scope(
                                SweepDataModule.EXAM_ACCESS,
                                SystemActor.RETENTION_ENGINE,
                                "retention_sweep",
                                "app_examaccess"),
                        new Scope(
                                SweepDataModule.IAM,
                                SystemActor.IDP_RECONCILER,
                                "reconciliation",
                                "app_iam"),
                        new Scope(
                                SweepDataModule.OUTBOX,
                                SystemActor.OUTBOX_RELAY,
                                "outbox_relay",
                                "app_outbox_relay"));
        for (Scope scope : scopes) {
            List<String> events = new ArrayList<>();
            var transactions = new DefaultSweepTransactions(initializer(events), CONTEXTS);
            Mono<String> work =
                    transactions
                            .inPlatformTransaction(
                                    scope.module(), DefaultSweepTransactionsTest::query)
                            .contextWrite(execution(scope.module(), scope.actor()));
            assertThat(events).isEmpty();
            StepVerifier.create(work).expectNext("done").verifyComplete();
            assertThat(events)
                    .startsWith(
                            "BEGIN",
                            "SET LOCAL ROLE " + scope.role(),
                            "SET LOCAL app.platform_scope = '" + scope.marker() + "'",
                            "SET LOCAL search_path = pg_catalog, " + scope.module().moduleName(),
                            "SELECT 1",
                            "COMMIT")
                    .endsWith("RESET ROLE", "RESET ALL", "CLOSE");
        }
    }

    @Test
    void tenantWorkHasOnlyTenantScopeAndUsesTheSameSystemIdentity() {
        List<String> events = new ArrayList<>();
        var transactions = new DefaultSweepTransactions(initializer(events), CONTEXTS);
        StepVerifier.create(
                        transactions
                                .inTenantTransaction(
                                        SweepDataModule.DELIVERY,
                                        TENANT,
                                        connection ->
                                                Mono.deferContextual(
                                                        context -> {
                                                            ActorContext actor =
                                                                    context.get(ActorContext.class);
                                                            assertThat(actor.tenantId())
                                                                    .contains(TENANT);
                                                            assertThat(actor.systemActorName())
                                                                    .contains(
                                                                            SystemActor
                                                                                    .AUTO_SUBMIT_SWEEPER);
                                                            return query(connection);
                                                        }))
                                .contextWrite(
                                        execution(
                                                SweepDataModule.DELIVERY,
                                                SystemActor.AUTO_SUBMIT_SWEEPER)))
                .expectNext("done")
                .verifyComplete();
        assertThat(events)
                .startsWith(
                        "BEGIN",
                        "SET LOCAL ROLE app_delivery",
                        "SET LOCAL app.tenant_id = '" + TENANT + "'");
        assertThat(events).noneMatch(event -> event.contains("platform_scope"));
    }

    @Test
    void missingOrMismatchedExecutionCannotObtainAConnection() {
        List<String> events = new ArrayList<>();
        var transactions = new DefaultSweepTransactions(initializer(events), CONTEXTS);
        Mono<String> work =
                transactions.inPlatformTransaction(
                        SweepDataModule.DELIVERY, DefaultSweepTransactionsTest::query);
        StepVerifier.create(work).expectError(SecurityException.class).verify();
        StepVerifier.create(
                        work.contextWrite(
                                execution(SweepDataModule.DELIVERY, SystemActor.AUTO_SUBMIT_SWEEPER)
                                        .put(
                                                ActorContext.class,
                                                actor(SystemActor.GRADING_WORKER))))
                .expectError(SecurityException.class)
                .verify();
        StepVerifier.create(
                        work.contextWrite(
                                execution(SweepDataModule.DELIVERY, SystemActor.IDP_RECONCILER)))
                .expectError(SecurityException.class)
                .verify();
        StepVerifier.create(
                        work.contextWrite(
                                execution(
                                        SweepDataModule.EXAM_ACCESS, SystemActor.RETENTION_ENGINE)))
                .expectError(SecurityException.class)
                .verify();
        assertThat(events).isEmpty();
    }

    @Test
    void nonSystemAndTenantActorsCannotOpenSweepTransactions() {
        List<String> events = new ArrayList<>();
        var connections = initializer(events);
        var transactions = new DefaultSweepTransactions(connections, CONTEXTS);
        ActorContext system = actor(SystemActor.AUTO_SUBMIT_SWEEPER);
        ActorId sameName = system.actorId();
        List<ActorContext> rejected =
                List.of(
                        ActorContext.platformWorkforce(
                                sameName, system.correlationId(), system.sourceIp()),
                        ActorContext.tenantWorkforce(
                                sameName, TENANT, system.correlationId(), system.sourceIp()),
                        ActorContext.candidate(
                                sameName, TENANT, system.correlationId(), system.sourceIp()),
                        ActorContext.tenantSystem(
                                SystemActor.AUTO_SUBMIT_SWEEPER,
                                TENANT,
                                system.correlationId(),
                                system.sourceIp()));
        List<Mono<String>> operations =
                List.of(
                        transactions.inPlatformTransaction(
                                SweepDataModule.DELIVERY, DefaultSweepTransactionsTest::query),
                        transactions.inTenantTransaction(
                                SweepDataModule.DELIVERY,
                                TENANT,
                                DefaultSweepTransactionsTest::query));

        for (ActorContext actor : rejected) {
            Context context =
                    execution(SweepDataModule.DELIVERY, SystemActor.AUTO_SUBMIT_SWEEPER)
                            .put(ActorContext.class, actor);
            for (Mono<String> operation : operations) {
                StepVerifier.create(operation.contextWrite(context))
                        .expectErrorSatisfies(
                                failure ->
                                        assertThat(failure)
                                                .isInstanceOf(SecurityException.class)
                                                .hasMessage(
                                                        "Sweep actor does not match its declaration"))
                        .verify();
            }
        }
        verify(connections.delegate(), never()).create();
        assertThat(events).isEmpty();
    }

    @Test
    void cannotMixScopesOrNestTransactions() {
        List<String> events = new ArrayList<>();
        var transactions = new DefaultSweepTransactions(initializer(events), CONTEXTS);
        StepVerifier.create(
                        transactions
                                .inPlatformTransaction(
                                        SweepDataModule.DELIVERY,
                                        connection ->
                                                transactions.inTenantTransaction(
                                                        SweepDataModule.DELIVERY,
                                                        TENANT,
                                                        DefaultSweepTransactionsTest::query))
                                .contextWrite(
                                        execution(
                                                SweepDataModule.DELIVERY,
                                                SystemActor.AUTO_SUBMIT_SWEEPER)))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(events).contains("ROLLBACK").doesNotContain("SELECT 1", "COMMIT");
        assertThat(events.stream().filter("BEGIN"::equals)).hasSize(1);
    }

    @Test
    void unscopedStatementsAndUndecoratedFactoriesAreRejected() {
        List<String> events = new ArrayList<>();
        StepVerifier.create(
                        Mono.from(initializer(events).create())
                                .flatMap(
                                        connection ->
                                                Flux.from(
                                                                connection
                                                                        .createStatement("SELECT 1")
                                                                        .execute())
                                                        .then()))
                .expectError(IllegalStateException.class)
                .verify();
        assertThat(events).doesNotContain("SELECT 1");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DefaultSweepTransactions(
                                        mock(ConnectionFactory.class), CONTEXTS));
    }

    @Test
    void coordinationRejectsATickWithAMismatchedActorBeforeBegin() {
        List<String> events = new ArrayList<>();
        StepVerifier.create(
                        Mono.from(initializer(events).create())
                                .flatMap(DatabaseSecurityScopes::beginSweepCoordinationTransaction)
                                .contextWrite(
                                        execution(
                                                        SweepDataModule.DELIVERY,
                                                        SystemActor.AUTO_SUBMIT_SWEEPER)
                                                .put(
                                                        ActorContext.class,
                                                        actor(SystemActor.IDP_RECONCILER))))
                .expectError(SecurityException.class)
                .verify();
        assertThat(events).isEmpty();
    }

    private static Mono<String> query(SweepConnection connection) {
        return Flux.from(connection.createStatement("SELECT 1").execute())
                .flatMap(Result::getRowsUpdated)
                .then(Mono.just("done"));
    }

    private static Context execution(SweepDataModule module, SystemActor actor) {
        SweepMetadata metadata =
                new SweepMetadata(
                        "reference",
                        module.moduleName(),
                        "FEAT-PLAT-006",
                        actor,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2));
        return Context.of(SweepMetadata.class, metadata, ActorContext.class, actor(actor));
    }

    private static ActorContext actor(SystemActor actor) {
        return ActorContext.platformSystem(
                actor, new CorrelationId("01ARZ3NDEKTSV4RRFFQ69G5FAV"), new SourceIp("::1"));
    }

    private static SecurityContextInitializer initializer(List<String> events) {
        Connection connection = mock(Connection.class);
        doReturn(Mono.fromRunnable(() -> events.add("BEGIN"))).when(connection).beginTransaction();
        doReturn(Mono.fromRunnable(() -> events.add("COMMIT")))
                .when(connection)
                .commitTransaction();
        doReturn(Mono.fromRunnable(() -> events.add("ROLLBACK")))
                .when(connection)
                .rollbackTransaction();
        doReturn(Mono.fromRunnable(() -> events.add("CLOSE"))).when(connection).close();
        when(connection.createStatement(anyString()))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            Statement statement = mock(Statement.class);
                            Result result = mock(Result.class);
                            doReturn(Mono.just(0L)).when(result).getRowsUpdated();
                            doReturn(
                                            Mono.fromSupplier(
                                                    () -> {
                                                        events.add(sql);
                                                        return result;
                                                    }))
                                    .when(statement)
                                    .execute();
                            return statement;
                        });
        ConnectionFactory factory = mock(ConnectionFactory.class);
        doReturn(Mono.just(connection)).when(factory).create();
        return new SecurityContextInitializer(factory);
    }

    private record Scope(SweepDataModule module, SystemActor actor, String marker, String role) {}
}
