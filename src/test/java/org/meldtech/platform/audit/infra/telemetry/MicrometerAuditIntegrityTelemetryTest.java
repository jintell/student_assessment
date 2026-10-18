package org.meldtech.platform.audit.infra.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.meldtech.platform.audit.application.AuditEpochSealingJob.JobResult;
import org.meldtech.platform.audit.application.AuditIntegrityTelemetry;
import org.meldtech.platform.audit.application.AuditIntegrityTelemetry.DispositionOutcome;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class MicrometerAuditIntegrityTelemetryTest {

    @ParameterizedTest
    @ValueSource(strings = {"api", "worker"})
    void registersAndExportsEveryIntegrityMeasurement(String profile) {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(AuditTelemetryConfiguration.class)
                    .withBean(MeterRegistry.class, () -> registry)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                AuditIntegrityTelemetry telemetry =
                                        context.getBean(AuditIntegrityTelemetry.class);
                                telemetry.verificationCompleted(true);
                                telemetry.emissionFailed();
                                telemetry.checkpointOverdueCount(2);
                                for (JobResult result : JobResult.values()) {
                                    telemetry.sealCompleted(result);
                                }
                                for (DispositionOutcome outcome : DispositionOutcome.values()) {
                                    telemetry.dispositionCompleted(outcome);
                                }
                                telemetry.compareAndSwapRetry();
                                telemetry.rootForkDetected();
                                telemetry.rootSequenceRange(3, 12);
                                assertThat(registry.scrape())
                                        .contains(
                                                "audit_chain_verification_result 1.0",
                                                "audit_emit_failure_total 1.0",
                                                "audit_checkpoint_overdue_count 2.0",
                                                "audit_epoch_seal_total{result=\"SEALED\"} 1.0",
                                                "audit_epoch_seal_total{result=\"DEFERRED\"} 1.0",
                                                "audit_epoch_disposition_total{outcome=\"DISPOSED\"} 1.0",
                                                "audit_epoch_disposition_total{outcome=\"HOLD_SUSPENDED\"} 1.0",
                                                "audit_epoch_disposition_total{outcome=\"VERIFY_FAILED\"} 1.0",
                                                "audit_epoch_seal_cas_retry_total 1.0",
                                                "audit_root_chain_fork_detected_total 1.0",
                                                "audit_root_chain_seq{statistic=\"min\"} 3.0",
                                                "audit_root_chain_seq{statistic=\"max\"} 12.0");
                                telemetry.verificationCompleted(false);
                                assertThat(
                                                registry.get("audit.chain.verification.result")
                                                        .gauge()
                                                        .value())
                                        .isZero();
                            });
        } finally {
            registry.close();
        }
    }

    @Test
    void unknownAndInvalidSamplesNeverBecomeHealthyZero() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var telemetry = new MicrometerAuditIntegrityTelemetry(registry);
            assertUnknown(registry);
            telemetry.checkpointOverdueCount(4);
            telemetry.rootSequenceRange(8, 20);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> telemetry.checkpointOverdueCount(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> telemetry.rootSequenceRange(-1, 2));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> telemetry.rootSequenceRange(9, 3));
            assertThat(registry.get("audit.checkpoint.overdue.count").gauge().value()).isEqualTo(4);
            assertThat(registry.get("audit.root.chain.seq").tag("statistic", "min").gauge().value())
                    .isEqualTo(8);
            telemetry.rootForkDetected();
            telemetry.invalidateSamples();
            assertUnknown(registry);
            assertThat(registry.get("audit.root.chain.fork.detected").counter().count())
                    .isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void registrationMatchesTheBudgetWithoutIdentityLabels() throws Exception {
        JsonNode contract =
                new ObjectMapper()
                        .readTree(
                                Files.readString(
                                        Path.of("config/observability/metric-cardinality.json")));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new MicrometerAuditIntegrityTelemetry(registry);
            Map<String, Set<String>> samples = new HashMap<>();
            for (String line : registry.scrape().lines().toList()) {
                if (!line.startsWith("audit_")) {
                    continue;
                }
                String series = line.substring(0, line.indexOf(' '));
                String name = series.split("\\{", 2)[0];
                samples.computeIfAbsent(name, ignored -> new HashSet<>()).add(series);
            }
            int registrations = 0;
            for (JsonNode metric : contract.get("metrics")) {
                String name = metric.get("name").stringValue();
                if (name.startsWith("audit_")) {
                    registrations++;
                    assertThat(samples).containsKey(name);
                    assertThat(samples.get(name)).hasSize(metric.get("maxSeries").intValue());
                }
            }
            assertThat(registrations).isEqualTo(8);
            assertThat(samples).hasSize(8);
            assertThat(registry.getMeters())
                    .allSatisfy(
                            meter -> {
                                assertThat(meter.getId().getDescription()).isNotBlank();
                                assertThat(meter.getId().getTags())
                                        .allSatisfy(
                                                tag ->
                                                        assertThat(tag.getKey())
                                                                .isIn(
                                                                        "result",
                                                                        "outcome",
                                                                        "statistic"));
                            });
        } finally {
            registry.close();
        }
    }

    @Test
    void pindistDoesNotRegisterAuditTelemetry() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditTelemetryConfiguration.class)
                .withPropertyValues("spring.profiles.active=pindist")
                .run(context -> assertThat(context).doesNotHaveBean(AuditIntegrityTelemetry.class));
    }

    @Test
    void forkCounterStartsAtZeroAndNeverResetsWhenSamplesAreInvalidated() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var telemetry = new MicrometerAuditIntegrityTelemetry(registry);
            assertThat(registry.get("audit.root.chain.fork.detected").counter().count()).isZero();
            telemetry.rootForkDetected();
            telemetry.rootForkDetected();
            telemetry.invalidateSamples();
            assertThat(registry.get("audit.root.chain.fork.detected").counter().count())
                    .isEqualTo(2);
            assertThat(registry.scrape()).contains("audit_root_chain_fork_detected_total 2.0");
        } finally {
            registry.close();
        }
    }

    private static void assertUnknown(PrometheusMeterRegistry registry) {
        assertThat(registry.get("audit.chain.verification.result").gauge().value()).isNaN();
        assertThat(registry.get("audit.checkpoint.overdue.count").gauge().value()).isNaN();
        assertThat(registry.get("audit.root.chain.seq").tag("statistic", "min").gauge().value())
                .isNaN();
        assertThat(registry.get("audit.root.chain.seq").tag("statistic", "max").gauge().value())
                .isNaN();
    }
}
