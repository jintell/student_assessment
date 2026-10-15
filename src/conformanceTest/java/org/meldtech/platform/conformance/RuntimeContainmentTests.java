package org.meldtech.platform.conformance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.platform.api.AuditStatementKind;
import org.meldtech.platform.platform.api.SweepLock;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.platform.infra.runtime.SessionSweepLock;
import org.meldtech.platform.platform.infra.runtime.UnapprovedIntervalFixture;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

class RuntimeContainmentTests {
    @Test
    void productionSchedulingAndLocksHaveOneOwner() {
        RuntimeContainmentRule.rule()
                .check(new ClassFileImporter().importPath(Path.of("build/classes/java/main")));
    }

    @Test
    void productionSqlResourcesContainNoNewAdvisoryLocks() throws IOException {
        RuntimeSqlResources.check(Path.of("src/main/resources"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pg_advisory_lock",
                "pg_advisory_xact_lock",
                "pg_try_advisory_lock_shared",
                "pg_try_advisory_xact_lock_shared",
                "pg_advisory_unlock",
                "pg_advisory_unlock_all"
            })
    void rejectsLockFunctionsInNewSqlResources(String function) {
        assertThatThrownBy(
                        () ->
                                RuntimeSqlResources.check(
                                        "db/new.sql", "SELECT pg_catalog.\"" + function + "\"(1)"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_LOCK_SQL_RESOURCE");
    }

    @Test
    void rejectsConstructedAndUninspectableResourceSql() {
        assertThatThrownBy(
                        () ->
                                RuntimeSqlResources.check(
                                        "db/new.sql", "EXECUTE 'SELECT pg_' || 'advisory_lock(1)'"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_LOCK_SQL_RESOURCE");
        assertThatThrownBy(() -> RuntimeSqlResources.check("db/new.sql", "EXECUTE supplied_sql;"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_DYNAMIC_SQL_RESOURCE");
    }

    @Test
    void legacyMigrationBaselineCannotBeChangedOrCopied() throws IOException {
        String resource = "db/migration/audit/V4__create_audit_event_partitions.sql";
        String sql = Files.readString(Path.of("src/main/resources").resolve(resource));
        RuntimeSqlResources.check(resource, sql);
        assertThatThrownBy(
                        () ->
                                RuntimeSqlResources.check(
                                        resource, sql + "\nSELECT pg_advisory_lock(2);"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_LOCK_BASELINE_CHANGED");
        assertThatThrownBy(() -> RuntimeSqlResources.check("db/migration/audit/V99__copy.sql", sql))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_LOCK_SQL_RESOURCE");
    }

    @ParameterizedTest
    @MethodSource("bypasses")
    void rejectsAlternateSchedulingAndLockMechanisms(Class<?> fixture) {
        assertThatThrownBy(
                        () ->
                                RuntimeContainmentRule.rule()
                                        .check(new ClassFileImporter().importClasses(fixture)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RUNTIME_");
    }

    @Test
    void acceptsApiUseAndFiniteRequestRetry() {
        RuntimeContainmentRule.rule().check(new ClassFileImporter().importClasses(ApiClient.class));
    }

    static Stream<Class<?>> bypasses() {
        return Stream.of(
                Interval.class,
                IntervalReference.class,
                PeriodicExecutor.class,
                PeriodicScheduler.class,
                AnnotatedTimer.class,
                JavaTimer.class,
                DelayedRepeat.class,
                DelayedRecursive.class,
                RecursiveReactor.class,
                UnapprovedIntervalFixture.class,
                LockSql.class,
                DynamicSql.class,
                ConcatenatedSql.class,
                SqlReference.class,
                SqlSupplier.class,
                AuditSql.class,
                ConcreteAdapter.class);
    }

    static class Interval {
        Flux<Long> ticks() {
            return Flux.interval(Duration.ofSeconds(1));
        }
    }

    static class IntervalReference {
        Function<Duration, Flux<Long>> source() {
            return Flux::interval;
        }
    }

    static class PeriodicExecutor {
        void start(ScheduledExecutorService service) {
            var unused = service.scheduleAtFixedRate(() -> {}, 0, 1, TimeUnit.SECONDS);
        }
    }

    static class PeriodicScheduler {
        void start(Scheduler scheduler) {
            scheduler.schedulePeriodically(() -> {}, 0, 1, TimeUnit.SECONDS);
        }
    }

    static class AnnotatedTimer {
        @Scheduled(fixedDelay = 1000)
        void tick() {}
    }

    static class JavaTimer {
        void start(Timer timer, TimerTask task) {
            timer.schedule(task, 1000, 1000);
        }
    }

    static class DelayedRepeat {
        Flux<Long> ticks() {
            return Mono.delay(Duration.ofSeconds(1)).repeat();
        }
    }

    static class DelayedRecursive {
        void tick(Scheduler scheduler) {
            scheduler.schedule(() -> tick(scheduler), 1, TimeUnit.SECONDS);
        }
    }

    static class LockSql {
        void acquire(DatabaseClient client) {
            client.sql("SELECT pg_catalog.\"pg_advisory_xact_lock_shared\"(1)").then();
        }
    }

    static class RecursiveReactor {
        Mono<Void> tick() {
            return Mono.delay(Duration.ofSeconds(1)).then(Mono.defer(this::tick));
        }
    }

    static class SqlReference {
        Function<String, DatabaseClient.GenericExecuteSpec> execute(DatabaseClient client) {
            return client::sql;
        }
    }

    static class SqlSupplier {
        void execute(DatabaseClient client, java.util.function.Supplier<String> supplier) {
            client.sql(supplier).then();
        }
    }

    static class AuditSql {
        void execute(TransactionalConnection connection, AuditStatementKind kind, String sql) {
            connection.createAuditStatement(kind, sql);
        }
    }

    static class DynamicSql {
        void acquire(DatabaseClient client, String function) {
            client.sql("SELECT " + function + "(1)").then();
        }
    }

    static class ConcatenatedSql {
        void acquire(DatabaseClient client, String suffix) {
            client.sql("SELECT pg_" + suffix + "(1)").then();
        }
    }

    static class ConcreteAdapter {
        Mono<SweepLock.Handle> acquire(SessionSweepLock lock) {
            return lock.tryAcquire("outbox-relay");
        }
    }

    static class ApiClient {
        Mono<SweepLock.Handle> acquire(SweepLock lock) {
            return lock.tryAcquire("outbox-relay");
        }

        Mono<Long> retryRequest() {
            return Mono.delay(Duration.ofMillis(1))
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(1)));
        }

        void query(DatabaseClient client) {
            String sql = "SELECT 1";
            client.sql(sql).then();
        }
    }
}
