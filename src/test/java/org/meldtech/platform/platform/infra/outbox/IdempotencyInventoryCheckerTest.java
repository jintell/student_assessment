package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdempotencyInventoryCheckerTest {

    @Test
    void repositoryInventoryHasOwnersAndShippedProofs() {
        assertThatNoException()
                .isThrownBy(
                        () ->
                                IdempotencyInventoryChecker.verify(
                                        Path.of("contracts/idempotency-inventory.yaml"),
                                        Path.of("contracts/feature-delivery-status.yaml"),
                                        Path.of(".")));
    }

    @Test
    void shippedOwnerWithoutProofIsRejected(@TempDir Path directory) throws Exception {
        String inventory =
                Files.readString(Path.of("contracts/idempotency-inventory.yaml"))
                        .replace(
                                "provingTest: >-\n"
                                        + "      org.meldtech.platform.platform.infra.idempotency."
                                        + "IdempotencyRequestFilterTest."
                                        + "reservedResponseIsPersistedForReplay",
                                "provingTest: null");
        Path inventoryPath = directory.resolve("inventory.yaml");
        Files.writeString(inventoryPath, inventory);

        assertThatIllegalStateException()
                .isThrownBy(
                        () ->
                                IdempotencyInventoryChecker.verify(
                                        inventoryPath,
                                        Path.of("contracts/feature-delivery-status.yaml"),
                                        Path.of(".")))
                .withMessageContaining("provingTest");
    }
}
