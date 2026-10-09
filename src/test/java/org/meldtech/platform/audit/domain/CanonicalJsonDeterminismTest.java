package org.meldtech.platform.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.DecimalValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.InstantValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.NullValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.ObjectValue;
import org.meldtech.platform.shared.kernel.audit.CanonicalValue.StringValue;

class CanonicalJsonDeterminismTest {

    private static final String EXPECTED =
            HexFormat.of()
                    .formatHex(
                            ("{\"decimal\":1234.5,\"name\":\"Caf\u00e9\",\"null\":null,"
                                            + "\"time\":\"2026-10-07T03:04:05.123456Z\"}")
                                    .getBytes(StandardCharsets.UTF_8));

    @Test
    void nonUtcDefaultTimeZonePreservesCanonicalBytes() throws Exception {
        assertThat(runJvm("Pacific/Auckland", "en", "NZ")).isEqualTo(EXPECTED);
    }

    @Test
    void nonEnglishDefaultLocalePreservesCanonicalBytes() throws Exception {
        assertThat(runJvm("UTC", "tr", "TR")).isEqualTo(EXPECTED);
    }

    @Test
    void restartingTheJvmPreservesCanonicalBytes() throws Exception {
        String firstRun = runJvm("UTC", "en", "US");
        String restartedRun = runJvm("UTC", "en", "US");
        assertThat(firstRun).isEqualTo(EXPECTED);
        assertThat(restartedRun).isEqualTo(firstRun);
    }

    private static String runJvm(String timeZone, String language, String country)
            throws Exception {
        String classpath =
                Path.of(
                                CanonicalJsonDeterminismTest.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        + File.pathSeparator
                        + Path.of(
                                CanonicalJsonCodec.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI());
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-Duser.timezone=" + timeZone,
                                "-Duser.language=" + language,
                                "-Duser.country=" + country,
                                "-cp",
                                classpath,
                                Probe.class.getName(),
                                timeZone,
                                language,
                                country)
                        .redirectErrorStream(true)
                        .start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("codec JVM completed").isTrue();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("codec JVM output: %s", output).isZero();
            return output.strip();
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class Probe {
        private Probe() {}

        public static void main(String[] args) throws IOException {
            if (!java.util.TimeZone.getDefault().getID().equals(args[0])
                    || !java.util.Locale.getDefault().getLanguage().equals(args[1])
                    || !java.util.Locale.getDefault().getCountry().equals(args[2])) {
                throw new IllegalStateException("Requested ambient defaults were not applied");
            }
            ObjectValue input =
                    new ObjectValue(
                            Map.of(
                                    "time",
                                            new InstantValue(
                                                    Instant.parse("2026-10-07T03:04:05.123456Z")),
                                    "null", NullValue.INSTANCE,
                                    "name", new StringValue("Cafe\u0301"),
                                    "decimal", new DecimalValue(new BigDecimal("1234.5000"))));
            System.out.write(
                    HexFormat.of()
                            .formatHex(new CanonicalJsonCodec().encode(input).bytes())
                            .getBytes(StandardCharsets.UTF_8));
        }
    }
}
