package org.meldtech.migrationverify.compatibility;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class DockerPreviousReleaseRuntimeFactory implements PreviousReleaseRuntimeFactory {

    private static final String JAVA = "/layers/paketo-buildpacks_bellsoft-liberica/jre/bin/java";
    private static final String CLASSPATH =
            "/workspace/compatibility-probe.jar:/workspace/BOOT-INF/classes:"
                    + "/workspace/BOOT-INF/lib/*";
    private static final String PROBE =
            "org.meldtech.compatibilityprobe.PreviousReleaseCompatibilityProbe";
    private static final Pattern RESULT =
            Pattern.compile(
                    "CBT_COMPATIBILITY_RESULT=(true|false),(true|false),(true|false),(true|false)");

    private final String imageRepository;
    private final Path probeJar;
    private final Network network;
    private final String databaseHost;
    private final String databasePassword;

    public DockerPreviousReleaseRuntimeFactory(
            String imageRepository,
            Path probeJar,
            Network network,
            String databaseHost,
            String databasePassword) {
        this.imageRepository = requireRepository(imageRepository);
        this.probeJar = Objects.requireNonNull(probeJar, "probeJar").toAbsolutePath().normalize();
        this.network = Objects.requireNonNull(network, "network");
        this.databaseHost = Objects.requireNonNull(databaseHost, "databaseHost");
        this.databasePassword = Objects.requireNonNull(databasePassword, "databasePassword");
    }

    @Override
    public PreviousReleaseApplication start(String immutableImageDigest) {
        String reference = imageRepository + "@" + immutableImageDigest;
        var container =
                new GenericContainer<>(DockerImageName.parse(reference))
                        .withNetwork(network)
                        .withEnv("SPRING_PROFILES_ACTIVE", "api")
                        .withEnv("SPRING_FLYWAY_ENABLED", "false")
                        .withEnv(
                                "CBT_DATABASE_URL",
                                "r2dbc:postgresql://" + databaseHost + ":5432/migration_stage_12")
                        .withEnv("CBT_DATABASE_ROLES_APP_API_PASSWORD", databasePassword)
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(probeJar),
                                "/workspace/compatibility-probe.jar")
                        .waitingFor(
                                Wait.forLogMessage(".*Started CbtPlatformApplication.*\\n", 1)
                                        .withStartupTimeout(Duration.ofMinutes(2)));
        try {
            container.start();
            String resolvedDigest = resolvedDigest(container, imageRepository);
            return new DockerPreviousReleaseApplication(container, resolvedDigest);
        } catch (RuntimeException failure) {
            container.close();
            throw failure;
        }
    }

    private static String resolvedDigest(GenericContainer<?> container, String imageRepository) {
        String imageId =
                container
                        .getDockerClient()
                        .inspectContainerCmd(container.getContainerId())
                        .exec()
                        .getImageId();
        List<String> repoDigests =
                container.getDockerClient().inspectImageCmd(imageId).exec().getRepoDigests();
        if (repoDigests == null) {
            throw new IllegalStateException(
                    "Retained image exposes no immutable repository digest");
        }
        String prefix = imageRepository + "@";
        return repoDigests.stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Retained image did not resolve from " + imageRepository));
    }

    private static String requireRepository(String value) {
        if (value == null || value.isBlank() || value.contains("@")) {
            throw new IllegalArgumentException(
                    "Image repository must not be blank or contain a digest");
        }
        return value;
    }

    private static final class DockerPreviousReleaseApplication
            implements PreviousReleaseApplication {

        private final GenericContainer<?> container;
        private final String resolvedDigest;

        private DockerPreviousReleaseApplication(
                GenericContainer<?> container, String resolvedDigest) {
            this.container = container;
            this.resolvedDigest = resolvedDigest;
        }

        @Override
        public String resolvedImageDigest() {
            return resolvedDigest;
        }

        @Override
        public boolean containsMigration(String migrationPath) {
            String output = executeProbe("contains-migration", migrationPath);
            if (output.contains("CBT_MIGRATION_PRESENT=true")) {
                return true;
            }
            if (output.contains("CBT_MIGRATION_PRESENT=false")) {
                return false;
            }
            throw new IllegalStateException("N-1 migration inspection returned no result");
        }

        @Override
        public CompatibilityExecution execute(String caseId) {
            Matcher matcher = RESULT.matcher(executeProbe(caseId));
            if (!matcher.find()) {
                throw new IllegalStateException("N-1 compatibility probe returned no result");
            }
            return new CompatibilityExecution(
                    Boolean.parseBoolean(matcher.group(1)),
                    Boolean.parseBoolean(matcher.group(2)),
                    Boolean.parseBoolean(matcher.group(3)),
                    Boolean.parseBoolean(matcher.group(4)));
        }

        private String executeProbe(String... arguments) {
            String[] command = new String[arguments.length + 4];
            command[0] = JAVA;
            command[1] = "-cp";
            command[2] = CLASSPATH;
            command[3] = PROBE;
            System.arraycopy(arguments, 0, command, 4, arguments.length);
            try {
                var result = container.execInContainer(command);
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException(
                            "N-1 compatibility probe failed: " + result.getStderr());
                }
                return result.getStdout();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "N-1 compatibility probe was interrupted", exception);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(
                        "Cannot execute N-1 compatibility probe", exception);
            }
        }

        @Override
        public void close() {
            container.close();
        }
    }
}
