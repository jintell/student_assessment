package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RlsMigrationTemplateTest {

    @Test
    void templateCarriesTheCompleteStrictTenantPolicy() throws IOException {
        try (var resource =
                getClass().getClassLoader().getResourceAsStream("db/templates/tenant_rls.sql")) {
            assertThat(resource).isNotNull();
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("ALTER TABLE ${schema}.${table} ENABLE ROW LEVEL SECURITY")
                    .contains("ALTER TABLE ${schema}.${table} FORCE ROW LEVEL SECURITY")
                    .contains("CREATE POLICY tenant_isolation")
                    .contains("USING (tenant_id = current_setting('app.tenant_id', false)::uuid)")
                    .contains(
                            "WITH CHECK (tenant_id = current_setting('app.tenant_id', false)::uuid)");
        }
    }
}
