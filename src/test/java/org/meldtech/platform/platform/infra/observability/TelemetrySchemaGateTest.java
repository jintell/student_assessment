package org.meldtech.platform.platform.infra.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meldtech.platform.academic.domain.telemetryfixture.CandidateRecord;

class TelemetrySchemaGateTest {

    @Test
    void acceptsTheStructuredLoggingSchema() throws ClassNotFoundException {
        Class<?> loggingType =
                Class.forName(
                        "org.meldtech.platform.platform.infra.observability.StructuredLogEvent");

        assertThatNoException()
                .isThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(loggingType));
    }

    @Test
    void rejectsSecretDomainAndUnboundedLoggingFields() {
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(SecretLogBoundary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("authorizationToken");
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(DomainLogBoundary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("exposes domain type");
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(MapLogBoundary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("unapproved telemetry container");
    }

    @Test
    void refusesPlaintextPinAcrossEveryLoggingEscapePath() {
        assertLoggingBoundaryRejected(PinDomainField.class, "pin");
        assertLoggingBoundaryRejected(PinMapBoundary.class, "unapproved telemetry container");
        assertLoggingBoundaryRejected(NestedPinBoundary.class, "pin");
        assertLoggingBoundaryRejected(ExceptionMessageBoundary.class, "non-record telemetry type");

        AtomicBoolean rendered = new AtomicBoolean();
        assertLoggingBoundaryRejected(ToStringBoundary.class, "uses Object");
        new HostileToString(rendered);
        assertThat(rendered).isFalse();
    }

    @Test
    void refusesOtpAuthenticationTokenAndPasswordAcrossEveryLoggingEscapePath() {
        for (SecretAttempt attempt :
                List.of(
                        new SecretAttempt("otp", OtpDomainField.class, NestedOtpBoundary.class),
                        new SecretAttempt(
                                "authenticationToken",
                                AuthenticationTokenDomainField.class,
                                NestedAuthenticationTokenBoundary.class),
                        new SecretAttempt(
                                "password",
                                PasswordDomainField.class,
                                NestedPasswordBoundary.class))) {
            assertLoggingBoundaryRejected(attempt.domainBoundary(), attempt.fieldName());
            assertLoggingBoundaryRejected(
                    SecretMapBoundary.class, "unapproved telemetry container");
            assertLoggingBoundaryRejected(attempt.nestedBoundary(), attempt.fieldName());
            assertLoggingBoundaryRejected(
                    ExceptionMessageBoundary.class, "non-record telemetry type");
            assertLoggingBoundaryRejected(ToStringBoundary.class, "uses Object");
        }
    }

    @Test
    void acceptsRegisteredSpanAttributesAndMetricLabels() {
        assertThatNoException().isThrownBy(TelemetrySchemaGate::verifySpanAttributes);
        assertThatNoException()
                .isThrownBy(
                        () ->
                                TelemetrySchemaGate.verifyMetricLabels(
                                        Path.of("config/observability/metric-cardinality.json")));
    }

    @Test
    void rejectsSecretsAndPersonalDataFromSpanAndMetricFields(@TempDir Path directory)
            throws IOException {
        assertThatThrownBy(
                        () ->
                                TelemetrySchemaGate.verifyFieldNames(
                                        "span attribute",
                                        List.of("authorizationToken", "candidateEmail")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("span attribute");

        Path metricContract = directory.resolve("metric-cardinality.json");
        Files.writeString(
                metricContract,
                """
                {"metrics":[{"name":"unsafe_total","labels":["answerContent"]}]}
                """);

        assertThatThrownBy(() -> TelemetrySchemaGate.verifyMetricLabels(metricContract))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("metric label answerContent");
    }

    @Test
    void rejectsCandidateEmailAndNameFromEveryTelemetrySurface() {
        for (String fieldName : List.of("candidateEmail", "candidateName")) {
            Class<?> loggingBoundary =
                    fieldName.equals("candidateEmail")
                            ? CandidateEmailBoundary.class
                            : CandidateNameBoundary.class;
            assertLoggingBoundaryRejected(loggingBoundary, fieldName);
            assertThatThrownBy(
                            () ->
                                    TelemetrySchemaGate.verifyFieldNames(
                                            "span attribute", List.of(fieldName)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ARC-OBS-002")
                    .hasMessageContaining(fieldName);
            assertThatThrownBy(
                            () ->
                                    TelemetrySchemaGate.verifyFieldNames(
                                            "metric label", List.of(fieldName)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ARC-OBS-002")
                    .hasMessageContaining(fieldName);
        }
    }

    @Test
    void rejectsAnswerContentFromLogsAndSpanAttributes() {
        assertLoggingBoundaryRejected(AnswerContentBoundary.class, "answerContent");
        assertThatThrownBy(
                        () ->
                                TelemetrySchemaGate.verifyFieldNames(
                                        "span attribute", List.of("answerContent")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining("answerContent");
    }

    private record SecretLogBoundary(String authorizationToken) {}

    private record DomainLogBoundary(CandidateRecord candidate) {}

    private record MapLogBoundary(Map<String, String> fields) {}

    private static void assertLoggingBoundaryRejected(Class<?> boundary, String reason) {
        assertThatThrownBy(() -> TelemetrySchemaGate.verifyLoggingType(boundary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARC-OBS-002")
                .hasMessageContaining(reason);
    }

    private record PinDomainField(String pin) {}

    private record PinMapBoundary(Map<String, String> fields) {}

    private record PinValue(String pin) {}

    private record NestedPinBoundary(PinValue credential) {}

    private record ExceptionMessageBoundary(Throwable failure) {}

    private record ToStringBoundary(Object value) {}

    private record HostileToString(AtomicBoolean rendered) {

        @Override
        public String toString() {
            rendered.set(true);
            return "pin-should-never-be-rendered";
        }
    }

    private record SecretAttempt(
            String fieldName, Class<?> domainBoundary, Class<?> nestedBoundary) {}

    private record SecretMapBoundary(Map<String, String> fields) {}

    private record OtpDomainField(String otp) {}

    private record OtpValue(String otp) {}

    private record NestedOtpBoundary(OtpValue credential) {}

    private record AuthenticationTokenDomainField(String authenticationToken) {}

    private record AuthenticationTokenValue(String authenticationToken) {}

    private record NestedAuthenticationTokenBoundary(AuthenticationTokenValue credential) {}

    private record PasswordDomainField(String password) {}

    private record PasswordValue(String password) {}

    private record NestedPasswordBoundary(PasswordValue credential) {}

    private record CandidateEmailBoundary(String candidateEmail) {}

    private record CandidateNameBoundary(String candidateName) {}

    private record AnswerContentBoundary(String answerContent) {}
}
