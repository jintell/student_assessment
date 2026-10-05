package org.meldtech.platform.platform.infra.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.jspecify.annotations.Nullable;
import org.meldtech.platform.shared.kernel.time.Clock;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class TracingConnectionFactory implements ConnectionFactory {

    private final ConnectionFactory delegate;
    private final PlatformTracer tracer;
    private final DatabaseQueryTelemetry queryTelemetry;
    private final Clock clock;

    public TracingConnectionFactory(
            ConnectionFactory delegate,
            OpenTelemetry openTelemetry,
            DatabaseQueryTelemetry queryTelemetry,
            Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        tracer =
                new PlatformTracer(
                        Objects.requireNonNull(openTelemetry, "openTelemetry")
                                .getTracer(OpenTelemetryTracerConfiguration.INSTRUMENTATION_SCOPE));
        this.queryTelemetry = Objects.requireNonNull(queryTelemetry, "queryTelemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.from(delegate.create())
                .map(
                        connection ->
                                new TracingConnection(connection, tracer, queryTelemetry, clock));
    }

    @Override
    public ConnectionFactoryMetadata getMetadata() {
        return delegate.getMetadata();
    }

    public ConnectionFactory delegate() {
        return delegate;
    }

    private static final class TracingConnection implements Connection {

        private final Connection delegate;
        private final PlatformTracer tracer;
        private final DatabaseQueryTelemetry queryTelemetry;
        private final Clock clock;

        private TracingConnection(
                Connection delegate,
                PlatformTracer tracer,
                DatabaseQueryTelemetry queryTelemetry,
                Clock clock) {
            this.delegate = delegate;
            this.tracer = tracer;
            this.queryTelemetry = queryTelemetry;
            this.clock = clock;
        }

        @Override
        public Statement createStatement(String sql) {
            return new TracingStatement(
                    delegate.createStatement(sql),
                    DatabaseStatementName.from(sql),
                    tracer,
                    queryTelemetry,
                    clock);
        }

        @Override
        public org.reactivestreams.Publisher<Void> beginTransaction() {
            return delegate.beginTransaction();
        }

        @Override
        public org.reactivestreams.Publisher<Void> beginTransaction(
                io.r2dbc.spi.TransactionDefinition definition) {
            return delegate.beginTransaction(definition);
        }

        @Override
        public org.reactivestreams.Publisher<Void> close() {
            return delegate.close();
        }

        @Override
        public org.reactivestreams.Publisher<Void> commitTransaction() {
            return delegate.commitTransaction();
        }

        @Override
        public io.r2dbc.spi.Batch createBatch() {
            return delegate.createBatch();
        }

        @Override
        public org.reactivestreams.Publisher<Void> createSavepoint(String name) {
            return delegate.createSavepoint(name);
        }

        @Override
        public boolean isAutoCommit() {
            return delegate.isAutoCommit();
        }

        @Override
        public io.r2dbc.spi.ConnectionMetadata getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public io.r2dbc.spi.IsolationLevel getTransactionIsolationLevel() {
            return delegate.getTransactionIsolationLevel();
        }

        @Override
        public org.reactivestreams.Publisher<Void> releaseSavepoint(String name) {
            return delegate.releaseSavepoint(name);
        }

        @Override
        public org.reactivestreams.Publisher<Void> rollbackTransaction() {
            return delegate.rollbackTransaction();
        }

        @Override
        public org.reactivestreams.Publisher<Void> rollbackTransactionToSavepoint(String name) {
            return delegate.rollbackTransactionToSavepoint(name);
        }

        @Override
        public org.reactivestreams.Publisher<Void> setAutoCommit(boolean autoCommit) {
            return delegate.setAutoCommit(autoCommit);
        }

        @Override
        public org.reactivestreams.Publisher<Void> setLockWaitTimeout(java.time.Duration timeout) {
            return delegate.setLockWaitTimeout(timeout);
        }

        @Override
        public org.reactivestreams.Publisher<Void> setStatementTimeout(java.time.Duration timeout) {
            return delegate.setStatementTimeout(timeout);
        }

        @Override
        public org.reactivestreams.Publisher<Void> setTransactionIsolationLevel(
                io.r2dbc.spi.IsolationLevel isolationLevel) {
            return delegate.setTransactionIsolationLevel(isolationLevel);
        }

        @Override
        public org.reactivestreams.Publisher<Boolean> validate(io.r2dbc.spi.ValidationDepth depth) {
            return delegate.validate(depth);
        }
    }

    private static final class TracingStatement implements Statement {

        private final Statement delegate;
        private final DatabaseStatementName statementName;
        private final PlatformTracer tracer;
        private final DatabaseQueryTelemetry queryTelemetry;
        private final Clock clock;

        private TracingStatement(
                Statement delegate,
                DatabaseStatementName statementName,
                PlatformTracer tracer,
                DatabaseQueryTelemetry queryTelemetry,
                Clock clock) {
            this.delegate = delegate;
            this.statementName = statementName;
            this.tracer = tracer;
            this.queryTelemetry = queryTelemetry;
            this.clock = clock;
        }

        @Override
        public Statement add() {
            delegate.add();
            return this;
        }

        @Override
        public Statement bind(int index, Object value) {
            delegate.bind(index, value);
            return this;
        }

        @Override
        public Statement bind(String name, Object value) {
            delegate.bind(name, value);
            return this;
        }

        @Override
        public Statement bindNull(int index, Class<?> type) {
            delegate.bindNull(index, type);
            return this;
        }

        @Override
        public Statement bindNull(String name, Class<?> type) {
            delegate.bindNull(name, type);
            return this;
        }

        @Override
        public Publisher<? extends io.r2dbc.spi.Result> execute() {
            return Flux.deferContextual(
                    reactorContext -> {
                        RequestQueryContext queryContext =
                                reactorContext.getOrDefault(RequestQueryContext.class, null);
                        if (queryContext != null) {
                            queryContext.increment();
                        }
                        Instant startedAt = clock.now();
                        Context parent =
                                Objects.requireNonNull(
                                        reactorContext.getOrDefault(
                                                Context.class, Context.current()));
                        Span span = tracer.startDatabaseSpan(statementName.spanName(), parent);
                        AtomicBoolean completed = new AtomicBoolean();
                        return Flux.from(delegate.execute())
                                .doOnComplete(
                                        () ->
                                                finishOnce(
                                                        span,
                                                        completed,
                                                        PlatformTracer.Outcome.SUCCESS))
                                .doOnError(
                                        failure ->
                                                finishOnce(
                                                        span,
                                                        completed,
                                                        PlatformTracer.Outcome.ERROR))
                                .doOnCancel(
                                        () ->
                                                finishOnce(
                                                        span,
                                                        completed,
                                                        PlatformTracer.Outcome.CANCELLED))
                                .doFinally(
                                        ignored -> {
                                            recordQuery(queryContext, startedAt);
                                            span.end();
                                        });
                    });
        }

        @Override
        public Statement returnGeneratedValues(String... columns) {
            delegate.returnGeneratedValues(columns);
            return this;
        }

        @Override
        public Statement fetchSize(int rows) {
            delegate.fetchSize(rows);
            return this;
        }

        private void finishOnce(
                Span span, AtomicBoolean completed, PlatformTracer.Outcome outcome) {
            if (completed.compareAndSet(false, true)) {
                tracer.finish(span, outcome);
            }
        }

        private void recordQuery(@Nullable RequestQueryContext queryContext, Instant startedAt) {
            if (queryContext == null) {
                return;
            }
            Instant completedAt = clock.now();
            Duration duration =
                    completedAt.isBefore(startedAt)
                            ? Duration.ZERO
                            : Duration.between(startedAt, completedAt);
            try {
                queryTelemetry.record(queryContext.metadata(), duration);
            } catch (RuntimeException ignored) {
                // Telemetry cannot change database behavior.
            }
        }
    }

    private enum DatabaseStatementName {
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        MERGE,
        OTHER;

        static DatabaseStatementName from(String sql) {
            Objects.requireNonNull(sql, "sql");
            try {
                net.sf.jsqlparser.statement.Statement parsed = CCJSqlParserUtil.parse(sql);
                if (parsed instanceof Select) {
                    return SELECT;
                }
                if (parsed instanceof Insert) {
                    return INSERT;
                }
                if (parsed instanceof Update) {
                    return UPDATE;
                }
                if (parsed instanceof Delete) {
                    return DELETE;
                }
                if (parsed instanceof Merge) {
                    return MERGE;
                }
                return OTHER;
            } catch (net.sf.jsqlparser.JSQLParserException exception) {
                return OTHER;
            }
        }

        String spanName() {
            return "db." + name().toLowerCase(Locale.ROOT);
        }
    }
}
