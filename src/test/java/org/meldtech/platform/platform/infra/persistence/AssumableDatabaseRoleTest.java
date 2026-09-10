package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AssumableDatabaseRoleTest {

    @Test
    void moduleRolesUseOnlyPgCatalogAndTheirOwnSchema() {
        assertThat(AssumableDatabaseRole.values()).hasSize(13);
        assertThat(
                        Arrays.stream(AssumableDatabaseRole.values())
                                .filter(role -> role != AssumableDatabaseRole.EXAM_ENTRY)
                                .map(AssumableDatabaseRole::searchPathStatement))
                .allMatch(
                        statement ->
                                statement.matches("SET LOCAL search_path = pg_catalog, [a-z]+"))
                .noneMatch(
                        statement ->
                                statement.contains("public")
                                        || statement.contains("audit")
                                        || statement.contains("outbox")
                                        || statement.contains("platform"));
    }

    @Test
    void compositeRoleRequiresQualifiedCrossSchemaReferences() {
        assertThat(AssumableDatabaseRole.EXAM_ENTRY.roleName()).isEqualTo("app_txn_examentry");
        assertThat(AssumableDatabaseRole.EXAM_ENTRY.searchPathStatement())
                .isEqualTo("SET LOCAL search_path = pg_catalog");
    }
}
