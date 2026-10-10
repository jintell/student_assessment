package org.meldtech.platform.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.audit.application.AuditEpochSealRepository;
import org.meldtech.platform.audit.application.OpenAuditChain;
import org.meldtech.platform.audit.domain.AuditChainKey;
import org.meldtech.platform.audit.domain.AuditHash;
import org.meldtech.platform.audit.domain.AuditRootHead;
import org.meldtech.platform.audit.domain.CanonicalJsonCodec;
import org.meldtech.platform.audit.domain.EpochIdentity;
import org.meldtech.platform.shared.kernel.audit.RetentionClass;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class R2dbcAuditFullVerificationEvidenceTest {

    private static final TenantId TENANT = TenantId.parse("00000000-0000-0000-0000-000000000091");
    private static final EpochIdentity EPOCH =
            new EpochIdentity(RetentionClass.GENERAL_AUDIT_EVENT, YearMonth.of(2026, 8));
    private static final Instant SIGNED_AT = Instant.parse("2026-09-01T00:00:00Z");
    private static final AuditHash PREVIOUS = hash(1);
    private static final AuditHash HEAD = hash(2);
    private static final AuditHash ROOT = hash(3);

    @Test
    void mapsRetainedChainsRecordsCheckpointsSealsAndRootEvidence() {
        ScriptedR2dbc database = new ScriptedR2dbc(R2dbcAuditFullVerificationEvidenceTest::query);
        AuditEpochSealRepository rootHeads = mock(AuditEpochSealRepository.class);
        AuditRootHead rootHead = new AuditRootHead(1, ROOT);
        when(rootHeads.readRootHead(TENANT)).thenReturn(Mono.just(rootHead));
        R2dbcAuditFullVerificationEvidence evidence =
                new R2dbcAuditFullVerificationEvidence(
                        database.connectionFactory(),
                        TENANT,
                        new CanonicalJsonCodec(),
                        new ObjectMapper(),
                        rootHeads);

        OpenAuditChain chain = Objects.requireNonNull(evidence.retainedChains(EPOCH).blockFirst());
        assertThat(chain.checkpoints()).hasSize(1);
        assertThat(chain.checkpoints().getFirst().sequenceEnd()).isEqualTo(1);

        assertThat(evidence.records(chain).collectList().block())
                .singleElement()
                .satisfies(
                        record -> {
                            assertThat(record.sequence()).isEqualTo(1);
                            assertThat(
                                            new String(
                                                    record.canonicalEvent().bytes(),
                                                    StandardCharsets.UTF_8))
                                    .contains("\"array\":[1]", "\"decimal\":1.5", "\"null\":null");
                        });

        assertThat(evidence.rootEvidence().block())
                .satisfies(
                        roots -> {
                            assertThat(roots.currentHead()).isEqualTo(rootHead);
                            assertThat(roots.seals()).hasSize(1);
                            assertThat(roots.seals().getFirst().evidence().material().shards())
                                    .hasSize(1);
                        });
        assertThat(evidence.seal(EPOCH).block()).isNotNull();
        assertThat(Objects.requireNonNull(evidence.tenantRootChains().blockFirst()).tenantId())
                .isEqualTo(TENANT);
    }

    @Test
    void rejectsForeignChainsMissingSealsAndAnUnstableRootRead() {
        ScriptedR2dbc database = new ScriptedR2dbc(R2dbcAuditFullVerificationEvidenceTest::query);
        AuditEpochSealRepository rootHeads = mock(AuditEpochSealRepository.class);
        AtomicInteger reads = new AtomicInteger();
        when(rootHeads.readRootHead(TENANT))
                .thenAnswer(
                        ignored ->
                                reads.getAndIncrement() % 2 == 0
                                        ? Mono.just(new AuditRootHead(0, PREVIOUS))
                                        : Mono.just(new AuditRootHead(1, ROOT)));
        R2dbcAuditFullVerificationEvidence evidence =
                new R2dbcAuditFullVerificationEvidence(
                        database.connectionFactory(),
                        TENANT,
                        new CanonicalJsonCodec(),
                        new ObjectMapper(),
                        rootHeads);

        StepVerifier.create(evidence.rootEvidence())
                .expectErrorMessage("audit root advancing; retry verification")
                .verify();
        StepVerifier.create(
                        evidence.records(
                                new OpenAuditChain(
                                        new AuditChainKey(
                                                TenantId.parse(
                                                        "00000000-0000-0000-0000-000000000092"),
                                                EPOCH,
                                                0,
                                                1),
                                        PREVIOUS,
                                        1,
                                        HEAD,
                                        java.util.List.of())))
                .expectErrorMessage("chain belongs to another tenant")
                .verify();
        StepVerifier.create(
                        evidence.seal(
                                new EpochIdentity(
                                        RetentionClass.PIN_SECURITY_EVENT, YearMonth.of(2026, 8))))
                .expectErrorMessage("audit epoch is not sealed")
                .verify();
    }

    private static ScriptedR2dbc.QueryResult query(String sql) {
        if (sql.contains("EXISTS")) {
            return ScriptedR2dbc.QueryResult.row(chainRow());
        }
        if (sql.contains("audit_chain_checkpoint")) {
            return ScriptedR2dbc.QueryResult.row(checkpointRow());
        }
        if (sql.contains("FROM audit.audit_event")) {
            return ScriptedR2dbc.QueryResult.row(recordRow());
        }
        if (sql.contains("audit_chain_seal")) {
            return ScriptedR2dbc.QueryResult.row(sealRow());
        }
        if (sql.contains("audit_chain_head")) {
            return ScriptedR2dbc.QueryResult.row(shardRow());
        }
        throw new AssertionError("Unexpected SQL: " + sql);
    }

    private static Map<String, Object> chainRow() {
        return Map.of(
                "retention_class",
                EPOCH.retentionClass().name(),
                "period",
                EPOCH.period().atDay(1),
                "shard_id",
                0,
                "shard_count",
                1,
                "seq",
                1L,
                "head_hash",
                HEAD.bytes(),
                "hash_algo_version",
                (short) 1);
    }

    private static Map<String, Object> checkpointRow() {
        return Map.ofEntries(
                Map.entry("seq_start", 1L),
                Map.entry("seq_end", 1L),
                Map.entry("head_hash", HEAD.bytes()),
                Map.entry("hash_algo_version", (short) 1),
                Map.entry("event_occurred_from", time("2026-08-01T00:00:00Z")),
                Map.entry("event_occurred_to", time("2026-08-02T00:00:00Z")),
                Map.entry("signing_key_version", "kms-v1"),
                Map.entry("signature_algorithm", "ECDSA"),
                Map.entry("signature", new byte[] {7}),
                Map.entry("signature_request_id", "checkpoint-request"),
                Map.entry("signed_at", time(SIGNED_AT.toString())));
    }

    private static Map<String, Object> recordRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_type", "audit.TEST.v1");
        row.put("entity_type", "audit.test");
        row.put("entity_id", "entity-1");
        row.put("actor_type", "WORKFORCE_USER");
        row.put("actor_id", "actor-1");
        row.put("system_actor_name", null);
        row.put("occurred_at", time("2026-08-01T12:00:00Z"));
        row.put("correlation_id", "01K74Q5Y7B0000000000000000");
        row.put("retention_class", EPOCH.retentionClass().name());
        row.put("retention_policy_key", "general");
        row.put("retention_policy_version", 1L);
        row.put("retention_until", time("2027-08-01T12:00:00Z"));
        row.put("period", EPOCH.period().atDay(1));
        row.put("shard_id", 0);
        row.put("seq", 1L);
        row.put("prev_hash", PREVIOUS.bytes());
        row.put("record_hash", HEAD.bytes());
        row.put("hash_algo_version", (short) 1);
        row.put(
                "payload_json",
                "{\"array\":[1],\"bool\":true,\"decimal\":1.5,\"null\":null,"
                        + "\"object\":{\"text\":\"x\"}}");
        return row;
    }

    private static Map<String, Object> sealRow() {
        return Map.ofEntries(
                Map.entry("retention_class", EPOCH.retentionClass().name()),
                Map.entry("period", EPOCH.period().atDay(1)),
                Map.entry("root_seq", 1L),
                Map.entry("previous_root_hash", PREVIOUS.bytes()),
                Map.entry("epoch_root", ROOT.bytes()),
                Map.entry("shard_count", 1),
                Map.entry("counts_json", "[1]"),
                Map.entry("ranges_json", "[{\"shard_id\":0,\"seq_start\":1,\"seq_end\":1}]"),
                Map.entry("hash_algo_version", (short) 1),
                Map.entry("signing_key_version", "kms-v1"),
                Map.entry("signature_algorithm", "ECDSA"),
                Map.entry("signature", new byte[] {8}),
                Map.entry("signature_request_id", "seal-request"),
                Map.entry("signed_at", time(SIGNED_AT.toString())));
    }

    private static Map<String, Object> shardRow() {
        return Map.of(
                "shard_id",
                0,
                "shard_count",
                1,
                "seq",
                1L,
                "head_hash",
                HEAD.bytes(),
                "hash_algo_version",
                (short) 1);
    }

    private static OffsetDateTime time(String value) {
        return OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
    }

    private static AuditHash hash(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return new AuditHash((short) 1, value);
    }
}
