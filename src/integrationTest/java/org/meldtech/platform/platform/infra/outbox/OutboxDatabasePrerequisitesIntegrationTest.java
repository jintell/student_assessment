package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.platform.infra.persistence.ReferenceSweepPools;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OutboxDatabasePrerequisitesIntegrationTest extends OutboxPostgreSqlIntegrationTestSupport {

    @Test
    void migratedWorkerStartsAndReturnsItsStartupConnection() throws Exception {
        try (ReferenceSweepPools pools = new ReferenceSweepPools(postgres)) {
            worker(pools)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context)
                                        .hasSingleBean(OutboxDatabasePrerequisites.class);
                                assertThat(pools.acquiredConnections()).isZero();
                            });
        }
    }

    @Test
    void missingRelayRoleFailsWorkerStartup() throws Exception {
        executeAsClusterOwner("ALTER ROLE app_outbox_relay RENAME TO outbox_relay_missing_fixture");
        try {
            assertWorkerFails();
        } finally {
            executeAsClusterOwner(
                    "ALTER ROLE outbox_relay_missing_fixture RENAME TO app_outbox_relay");
        }
    }

    @Test
    void missingOutboxTableFailsWorkerStartup() throws Exception {
        executeAsClusterOwner(
                "ALTER TABLE outbox.outbox_event RENAME TO outbox_event_missing_fixture");
        try {
            assertWorkerFails();
        } finally {
            executeAsClusterOwner(
                    "ALTER TABLE outbox.outbox_event_missing_fixture RENAME TO outbox_event");
        }
    }

    @Test
    void workerWithoutRelayMembershipFailsStartup() throws Exception {
        executeAsClusterOwner(
                "SET ROLE app_migrator; REVOKE app_outbox_relay FROM app_worker; RESET ROLE");
        try {
            assertWorkerFails();
        } finally {
            executeAsClusterOwner(
                    "SET ROLE app_migrator; GRANT app_outbox_relay TO app_worker WITH ADMIN FALSE, INHERIT FALSE, SET TRUE; RESET ROLE");
        }
    }

    @Test
    void requestAndIsolatedProfilesNeedNoOutboxStartupConnection() {
        for (String profile : new String[] {"api", "pindist"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(OutboxDatabasePrerequisites.class)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context)
                                        .doesNotHaveBean(OutboxDatabasePrerequisites.class);
                                assertThat(context).doesNotHaveBean(ConnectionFactory.class);
                            });
        }
    }

    private void assertWorkerFails() throws Exception {
        try (ReferenceSweepPools pools = new ReferenceSweepPools(postgres)) {
            worker(pools)
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(context.getStartupFailure())
                                        .hasMessageContaining("OUTBOX_DATABASE_NOT_READY")
                                        .hasMessageContaining("complete the migration Job")
                                        .hasNoCause();
                                assertThat(pools.acquiredConnections()).isZero();
                            });
        }
    }

    private static ApplicationContextRunner worker(ReferenceSweepPools pools) {
        return new ApplicationContextRunner()
                .withUserConfiguration(OutboxDatabasePrerequisites.class)
                .withBean("workerConnectionFactory", ConnectionFactory.class, pools::worker)
                .withPropertyValues("spring.profiles.active=worker");
    }
}
