package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.lang.reflect.Proxy;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcAuditAppendRepositoryTest {

    @Test
    void locksAndReturnsThePreProvisionedHead() {
        Map<Integer, Object> bindings = new HashMap<>();
        TransactionalConnection connection = sql -> statement(bindings, true);

        StepVerifier.create(new R2dbcAuditAppendRepository().lockHead(connection, key()))
                .assertNext(
                        head -> {
                            assertThat(head.sequence()).isEqualTo(8);
                            assertThat(head.headHash().hashAlgorithmVersion()).isEqualTo((short) 1);
                            assertThat(bindings).hasSize(5);
                        })
                .verifyComplete();
    }

    @Test
    void missingHeadIsAProvisioningFailure() {
        TransactionalConnection connection = sql -> statement(new HashMap<>(), false);

        StepVerifier.create(new R2dbcAuditAppendRepository().lockHead(connection, key()))
                .expectError(AuditProvisioningException.class)
                .verify();
    }

    private static AuditChainKey key() {
        return new AuditChainKey(
                TenantId.parse("01950f47-6000-7000-8000-000000000001"),
                new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 10)),
                7,
                64);
    }

    private static Statement statement(Map<Integer, Object> bindings, boolean found) {
        return (Statement)
                Proxy.newProxyInstance(
                        R2dbcAuditAppendRepositoryTest.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("bind")) {
                                bindings.put((Integer) arguments[0], arguments[1]);
                                return proxy;
                            }
                            if (method.getName().equals("execute")) {
                                return Mono.just(result(found));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    @SuppressWarnings("unchecked")
    private static Result result(boolean found) {
        return (Result)
                Proxy.newProxyInstance(
                        R2dbcAuditAppendRepositoryTest.class.getClassLoader(),
                        new Class<?>[] {Result.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("map")) {
                                if (!found) {
                                    return Mono.empty();
                                }
                                BiFunction<Row, Object, Object> mapper =
                                        (BiFunction<Row, Object, Object>) arguments[0];
                                return Mono.just(mapper.apply(row(), null));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static Row row() {
        return (Row)
                Proxy.newProxyInstance(
                        R2dbcAuditAppendRepositoryTest.class.getClassLoader(),
                        new Class<?>[] {Row.class},
                        (proxy, method, arguments) ->
                                switch ((String) arguments[0]) {
                                    case "seq" -> 8L;
                                    case "hash_algo_version" -> (short) 1;
                                    case "head_hash" -> new byte[32];
                                    default ->
                                            throw new IllegalArgumentException(
                                                    (String) arguments[0]);
                                });
    }
}
