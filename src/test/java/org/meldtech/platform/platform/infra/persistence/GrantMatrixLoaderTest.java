package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GrantMatrixLoaderTest {

    private final GrantMatrixLoader loader = new GrantMatrixLoader();

    @Test
    void loadsAndNormalizesTheCanonicalMatrix() {
        GrantMatrix matrix = loader.loadDefault();

        assertThat(matrix.schemas()).hasSize(15);
        assertThat(matrix.roles())
                .extracting(GrantMatrix.RoleGrant::name)
                .contains("app_txn_examentry");
        assertThat(matrix.objectGrants())
                .noneMatch(grant -> grant.grantee().matches("app_(api|worker|pindist)"));
        assertThat(matrix.defaultPrivileges()).hasSize(2);
    }

    @Test
    void rejectsUnknownRolesAndMembershipCycles() {
        assertInvalid(
                minimalMatrix("[{\"member\":\"app_api\",\"role\":\"missing\"}]"),
                "Unknown membership role");
        assertInvalid(
                minimalMatrix(
                        "[{\"member\":\"app_api\",\"role\":\"app_txn_examentry\"},"
                                + "{\"member\":\"app_txn_examentry\",\"role\":\"app_api\"}]"),
                "Role-membership cycle");
    }

    private void assertInvalid(String json, String message) {
        assertThatThrownBy(
                        () ->
                                loader.load(
                                        new ByteArrayInputStream(
                                                json.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private String minimalMatrix(String memberships) {
        String schemas =
                "academic,audit,authoring,correction,delivery,examaccess,grading,iam,notification,"
                        + "outbox,people,platform,questionbank,result,tenancy";
        String schemaJson =
                java.util.Arrays.stream(schemas.split(","))
                        .map(name -> "{\"name\":\"" + name + "\",\"owner\":\"app_migrator\"}")
                        .collect(java.util.stream.Collectors.joining(","));
        return """
                {"formatVersion":1,"schemas":[%s],"roles":[
                  {"name":"app_migrator","login":true,"createRole":true},
                  {"name":"app_api","login":true,"createRole":false},
                  {"name":"app_worker","login":true,"createRole":false},
                  {"name":"app_pindist","login":true,"createRole":false},
                  {"name":"app_txn_examentry","login":false,"createRole":false}
                ],"memberships":%s,"objectGrants":[],"defaultPrivileges":[],"denials":[
                  {"kind":"NO_DIRECT_OBJECT_GRANTS","role":"app_api"},
                  {"kind":"NO_DIRECT_OBJECT_GRANTS","role":"app_worker"},
                  {"kind":"NO_DIRECT_OBJECT_GRANTS","role":"app_pindist"}
                ]}
                """
                .formatted(schemaJson, memberships);
    }
}
