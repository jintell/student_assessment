package org.meldtech.migrationverify.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvalidIndexReconcilerTest {

    private final InvalidIndexReconciler reconciler = new InvalidIndexReconciler();
    private final InvalidIndexReconciler.IndexName expected =
            new InvalidIndexReconciler.IndexName("delivery", "answer_idx");

    @Test
    void anEmptyCatalogProducesAnIdempotentNoActionPlan() {
        assertEquals(List.of(), reconciler.plan(expected, List.of()));
    }

    @Test
    void plansOnlyAttributableInvalidConcurrentArtifacts() {
        var invalid =
                List.of(
                        new IndexState("delivery", "answer_idx", "delivery", "answer", false),
                        new IndexState(
                                "delivery", "answer_idx_ccnew", "delivery", "answer", false));

        assertEquals(invalid, reconciler.plan(expected, invalid));
    }

    @Test
    void refusesToDropAValidCollision() {
        var valid = new IndexState("delivery", "answer_idx", "delivery", "answer", true);

        assertThrows(IllegalStateException.class, () -> reconciler.plan(expected, List.of(valid)));
    }
}
