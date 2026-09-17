package org.meldtech.migrationverify.measure;

import java.util.List;

public record DatasetManifest(
        int schemaVersion,
        String generatorVersion,
        String profileVersion,
        String profileChecksum,
        long seed,
        int scale,
        List<TableFile> tables,
        String bundleChecksum) {

    public record TableFile(String name, String path, long rowCount, String sha256) {}
}
