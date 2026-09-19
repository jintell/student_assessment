package org.meldtech.migrationverify.measure;

public record IndexState(
        String schema, String name, String tableSchema, String tableName, boolean valid) {

    public String qualifiedName() {
        return schema + "." + name;
    }
}
