package org.meldtech.platform.audit.infra;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class ScriptedR2dbc {

    private final Function<String, QueryResult> results;
    private final List<Execution> executions = new ArrayList<>();
    private final Connection connection = connection();

    ScriptedR2dbc(Function<String, QueryResult> results) {
        this.results = Objects.requireNonNull(results, "results");
    }

    ConnectionFactory connectionFactory() {
        return proxy(
                ConnectionFactory.class,
                (target, method, arguments) ->
                        switch (method.getName()) {
                            case "create" -> Mono.just(connection);
                            case "getMetadata" -> metadata();
                            default -> defaultValue(method.getReturnType());
                        });
    }

    Connection connection() {
        return proxy(
                Connection.class,
                (target, method, arguments) ->
                        switch (method.getName()) {
                            case "createStatement" -> statement((String) arguments[0]);
                            case "close",
                                    "beginTransaction",
                                    "commitTransaction",
                                    "rollbackTransaction" ->
                                    Mono.empty();
                            default -> defaultValue(method.getReturnType());
                        });
    }

    List<Execution> executions() {
        return List.copyOf(executions);
    }

    private Statement statement(String sql) {
        Map<Integer, Object> bindings = new LinkedHashMap<>();
        final Statement[] reference = new Statement[1];
        reference[0] =
                proxy(
                        Statement.class,
                        (target, method, arguments) ->
                                switch (method.getName()) {
                                    case "bind" -> {
                                        bindings.put((Integer) arguments[0], arguments[1]);
                                        yield reference[0];
                                    }
                                    case "bindNull", "add", "returnGeneratedValues", "fetchSize" ->
                                            reference[0];
                                    case "execute" -> {
                                        QueryResult result = results.apply(sql);
                                        executions.add(
                                                new Execution(sql, Map.copyOf(bindings), result));
                                        yield Flux.just(result(result));
                                    }
                                    default -> defaultValue(method.getReturnType());
                                });
        return reference[0];
    }

    private static Result result(QueryResult queryResult) {
        return proxy(
                Result.class,
                (target, method, arguments) ->
                        switch (method.getName()) {
                            case "map" -> map(queryResult.rows(), arguments[0]);
                            case "getRowsUpdated" -> Mono.just(queryResult.rowsUpdated());
                            default -> defaultValue(method.getReturnType());
                        });
    }

    @SuppressWarnings("unchecked")
    private static Publisher<?> map(List<Map<String, Object>> rows, Object mapper) {
        BiFunction<Row, RowMetadata, Object> rowMapper =
                (BiFunction<Row, RowMetadata, Object>) mapper;
        RowMetadata metadata = proxy(RowMetadata.class, (target, method, arguments) -> null);
        return Flux.fromIterable(rows).map(values -> rowMapper.apply(row(values), metadata));
    }

    private static Row row(Map<String, Object> values) {
        return proxy(
                Row.class,
                (target, method, arguments) -> {
                    if (!method.getName().equals("get")) {
                        return defaultValue(method.getReturnType());
                    }
                    Object key = arguments[0];
                    if (key instanceof String column) {
                        return values.get(column);
                    }
                    return values.values().stream().skip((Integer) key).findFirst().orElse(null);
                });
    }

    private static ConnectionFactoryMetadata metadata() {
        return proxy(
                ConnectionFactoryMetadata.class,
                (target, method, arguments) ->
                        method.getName().equals("getName") ? "scripted" : null);
    }

    private static @Nullable Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    record QueryResult(List<Map<String, Object>> rows, long rowsUpdated) {

        QueryResult {
            rows = List.copyOf(rows);
        }

        static QueryResult row(Map<String, Object> row) {
            return new QueryResult(List.of(row), 0);
        }

        static QueryResult rows(List<Map<String, Object>> rows) {
            return new QueryResult(rows, 0);
        }

        static QueryResult updated(long count) {
            return new QueryResult(List.of(), count);
        }

        static QueryResult empty() {
            return new QueryResult(List.of(), 0);
        }
    }

    record Execution(String sql, Map<Integer, Object> bindings, QueryResult result) {}
}
