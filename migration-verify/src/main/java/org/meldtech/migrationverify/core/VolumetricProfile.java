package org.meldtech.migrationverify.core;

import java.util.List;
import java.util.Map;

public record VolumetricProfile(
        int schemaVersion,
        String profileVersion,
        long defaultSeed,
        int scale,
        Map<String, Integer> workload,
        List<TableProfile> tables) {

    public VolumetricProfile {
        workload = Map.copyOf(workload);
        tables = List.copyOf(tables);
    }

    public record TableProfile(
            String name,
            boolean expectedToExist,
            String rowCount,
            PrimaryKeyProfile primaryKey,
            List<String> parents,
            int generationOrder,
            Map<String, String> columns,
            String distribution) {

        public TableProfile {
            parents = List.copyOf(parents);
            columns = Map.copyOf(columns);
        }
    }

    public record PrimaryKeyProfile(List<String> columns, String strategy) {

        public PrimaryKeyProfile {
            columns = List.copyOf(columns);
        }
    }
}
