package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.ResolvedRetention;
import org.meldtech.platform.platform.api.TransactionalConnection;
import org.meldtech.platform.shared.kernel.audit.AuditEvent;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.EntityRef;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.audit.RetentionHorizon;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class R2dbcAuditAppendCteTest {

    @Test
    void insertsTheEventAndAdvancesExactlyOneLockedHead() {
        Map<Integer, Object> bindings = new HashMap<>();
        TransactionalConnection connection = sql -> statement(bindings, 1, 1);

        StepVerifier.create(
                        new R2dbcAuditAppendRepository()
                                .appendAndAdvance(connection, lockedHead(), record()))
                .verifyComplete();

        assertThat(bindings).hasSize(23);
        assertThat(R2dbcAuditAppendRepository.APPEND_SQL)
                .contains(
                        "WITH inserted_event AS MATERIALIZED",
                        "EXISTS (SELECT 1 FROM inserted_event)");
    }

    @Test
    void aConditionalHeadMissFailsTheCallerTransaction() {
        TransactionalConnection connection = sql -> statement(new HashMap<>(), 1, 0);

        StepVerifier.create(
                        new R2dbcAuditAppendRepository()
                                .appendAndAdvance(connection, lockedHead(), record()))
                .expectError(AuditAppendIntegrityException.class)
                .verify();
    }

    private static LockedChainHead lockedHead() {
        return new LockedChainHead(8, new AuditHash((short) 1, new byte[32]));
    }

    private static PreparedAuditRecord record() {
        TenantId tenant = TenantId.parse("01950f47-6000-7000-8000-000000000001");
        byte[] recordHash = new byte[32];
        recordHash[0] = 1;
        return new PreparedAuditRecord(
                UUID.fromString("01950f47-6000-7000-8000-000000000002"),
                new AuditEvent(
                        "audit.EVENT_RECORDED.v1",
                        new EntityRef("audit.event", "entity-1"),
                        Set.of(RetentionClass.GENERAL_AUDIT_EVENT),
                        new ObjectValue(Map.of())),
                ActorContext.tenantWorkforce(
                        new ActorId("operator-1"),
                        tenant,
                        CorrelationId.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                        SourceIp.parse("127.0.0.1")),
                Instant.parse("2026-10-08T00:00:00Z"),
                new ResolvedRetention(
                        RetentionClass.GENERAL_AUDIT_EVENT,
                        "audit.default",
                        1,
                        RetentionHorizon.until(Instant.parse("2028-10-08T00:00:00Z"))),
                new AuditChainKey(
                        tenant,
                        new EpochIdentity(
                                RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 10)),
                        7,
                        64),
                9,
                lockedHead().headHash(),
                new AuditHash((short) 1, recordHash),
                "{}");
    }

    private static Statement statement(
            Map<Integer, Object> bindings, long insertedRows, long updatedRows) {
        return (Statement)
                Proxy.newProxyInstance(
                        R2dbcAuditAppendCteTest.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("bind")) {
                                bindings.put((Integer) arguments[0], arguments[1]);
                                return proxy;
                            }
                            if (method.getName().equals("bindNull")) {
                                bindings.put((Integer) arguments[0], arguments[1]);
                                return proxy;
                            }
                            if (method.getName().equals("execute")) {
                                return Mono.just(result(insertedRows, updatedRows));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    @SuppressWarnings("unchecked")
    private static Result result(long insertedRows, long updatedRows) {
        return (Result)
                Proxy.newProxyInstance(
                        R2dbcAuditAppendCteTest.class.getClassLoader(),
                        new Class<?>[] {Result.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("map")) {
                                BiFunction<Row, Object, Object> mapper =
                                        (BiFunction<Row, Object, Object>) arguments[0];
                                return Mono.just(
                                        mapper.apply(countRow(insertedRows, updatedRows), null));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static Row countRow(long insertedRows, long updatedRows) {
        return (Row)
                Proxy.newProxyInstance(
                        R2dbcAuditAppendCteTest.class.getClassLoader(),
                        new Class<?>[] {Row.class},
                        (proxy, method, arguments) ->
                                switch ((String) arguments[0]) {
                                    case "inserted_count" -> insertedRows;
                                    case "updated_count" -> updatedRows;
                                    default ->
                                            throw new IllegalArgumentException(
                                                    (String) arguments[0]);
                                });
    }
}
