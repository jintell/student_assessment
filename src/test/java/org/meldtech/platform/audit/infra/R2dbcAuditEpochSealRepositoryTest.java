package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.AuditSignature;
import org.meldtech.platform.audit.domain.DerivedEpochRoot;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.audit.domain.EpochSealMaterial;
import org.meldtech.platform.audit.domain.ShardSealMaterial;
import org.meldtech.platform.audit.domain.ShardSequenceRange;
import org.meldtech.platform.audit.domain.SignedEpochSeal;
import org.meldtech.platform.audit.domain.UnsignedEpochSeal;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.test.StepVerifier;

class R2dbcAuditEpochSealRepositoryTest {

    private static final TenantId TENANT = TenantId.parse("00000000-0000-0000-0000-000000000081");
    private static final EpochIdentity EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 9));
    private static final Instant SIGNED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final AuditHash PREVIOUS = hash(1);
    private static final AuditHash HEAD = hash(2);

    @Test
    void mapsRootEpochAndTrustedTimeAndBindsAtomicAppend() {
        ScriptedR2dbc database =
                new ScriptedR2dbc(
                        sql -> {
                            if (sql.contains("FROM audit.audit_chain_root_head")) {
                                return ScriptedR2dbc.QueryResult.row(
                                        Map.of(
                                                "root_seq",
                                                4L,
                                                "root_head_hash",
                                                PREVIOUS.bytes(),
                                                "hash_algo_version",
                                                (short) 1));
                            }
                            if (sql.contains("FROM audit.audit_chain_head")) {
                                return ScriptedR2dbc.QueryResult.rows(
                                        List.of(shard(0, 0L, hash(0)), shard(1, 2L, HEAD)));
                            }
                            if (sql.contains("clock_timestamp")) {
                                return ScriptedR2dbc.QueryResult.row(
                                        Map.of(
                                                "signed_at",
                                                OffsetDateTime.ofInstant(
                                                        SIGNED_AT, ZoneOffset.UTC)));
                            }
                            if (sql.contains("advanced_count")) {
                                return ScriptedR2dbc.QueryResult.row(
                                        Map.of("advanced_count", 1L, "inserted_count", 1L));
                            }
                            throw new AssertionError("Unexpected SQL: " + sql);
                        });
        R2dbcAuditEpochSealRepository repository =
                new R2dbcAuditEpochSealRepository(database.connectionFactory());

        AuditRootHead observed = Objects.requireNonNull(repository.readRootHead(TENANT).block());
        assertThat(observed).isEqualTo(new AuditRootHead(4, PREVIOUS));
        EpochSealMaterial material =
                Objects.requireNonNull(
                        repository.loadEpochMaterial(TENANT, EPOCH, observed).block());
        assertThat(material.shards())
                .containsExactly(
                        ShardSealMaterial.empty(0), ShardSealMaterial.populated(1, 2, HEAD));
        assertThat(repository.trustedSigningTime().block()).isEqualTo(SIGNED_AT);

        SignedEpochSeal seal = seal(material);
        StepVerifier.create(repository.insertSealAndCompareAndSwap(seal, observed))
                .expectNext(true)
                .verifyComplete();

        ScriptedR2dbc.Execution append = database.executions().getLast();
        assertThat(append.bindings())
                .containsEntry(3, 5L)
                .containsEntry(7, "[0,2]")
                .containsEntry(15, 4L);
        assertThat((String) append.bindings().get(8))
                .contains("\"shard_id\":0", "\"seq_start\":null", "\"seq_end\":2");
    }

    @Test
    void reportsCasLossAndRejectsIncompleteOrInconsistentPersistence() {
        ScriptedR2dbc lost = appendDatabase(0, 0);
        R2dbcAuditEpochSealRepository repository =
                new R2dbcAuditEpochSealRepository(lost.connectionFactory());
        AuditRootHead observed = new AuditRootHead(4, PREVIOUS);

        StepVerifier.create(repository.insertSealAndCompareAndSwap(seal(material()), observed))
                .expectNext(false)
                .verifyComplete();

        ScriptedR2dbc inconsistent = appendDatabase(1, 0);
        StepVerifier.create(
                        new R2dbcAuditEpochSealRepository(inconsistent.connectionFactory())
                                .insertSealAndCompareAndSwap(seal(material()), observed))
                .expectErrorMessage(
                        "Audit seal append changed an inconsistent number of rows: advanced=1, inserted=0")
                .verify();
    }

    @Test
    void failsClosedForMissingRootAndEpochTopology() {
        ScriptedR2dbc empty = new ScriptedR2dbc(sql -> ScriptedR2dbc.QueryResult.empty());
        R2dbcAuditEpochSealRepository repository =
                new R2dbcAuditEpochSealRepository(empty.connectionFactory());

        StepVerifier.create(repository.readRootHead(TENANT))
                .expectErrorMessage("Audit root head is not provisioned")
                .verify();
        StepVerifier.create(
                        repository.loadEpochMaterial(TENANT, EPOCH, new AuditRootHead(0, PREVIOUS)))
                .expectErrorMessage("Audit epoch heads are not provisioned")
                .verify();
    }

    private static ScriptedR2dbc appendDatabase(long advanced, long inserted) {
        return new ScriptedR2dbc(
                sql ->
                        ScriptedR2dbc.QueryResult.row(
                                Map.of(
                                        "advanced_count", advanced,
                                        "inserted_count", inserted)));
    }

    private static Map<String, Object> shard(int id, long sequence, AuditHash head) {
        return Map.of(
                "shard_id",
                id,
                "shard_count",
                2,
                "seq",
                sequence,
                "head_hash",
                head.bytes(),
                "hash_algo_version",
                (short) 1);
    }

    private static EpochSealMaterial material() {
        return new EpochSealMaterial(
                TENANT,
                EPOCH,
                2,
                (short) 1,
                PREVIOUS,
                5,
                List.of(ShardSealMaterial.empty(0), ShardSealMaterial.populated(1, 2, HEAD)));
    }

    private static SignedEpochSeal seal(EpochSealMaterial material) {
        DerivedEpochRoot root =
                new DerivedEpochRoot(
                        hash(3),
                        List.of(0L, 2L),
                        List.of(
                                new ShardSequenceRange(
                                        0, OptionalLong.empty(), OptionalLong.empty()),
                                new ShardSequenceRange(1, OptionalLong.of(1), OptionalLong.of(2))));
        UnsignedEpochSeal unsigned = new UnsignedEpochSeal(material, root, SIGNED_AT);
        return new SignedEpochSeal(
                unsigned,
                new AuditSignature("kms-v1", "ECDSA", new byte[] {9}, "request-1", SIGNED_AT));
    }

    private static AuditHash hash(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return new AuditHash((short) 1, value);
    }
}
