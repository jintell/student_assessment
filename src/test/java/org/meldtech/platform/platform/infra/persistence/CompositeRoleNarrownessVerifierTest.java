package org.meldtech.platform.platform.infra.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeRoleNarrownessVerifierTest {

    @Test
    void canonicalCompositeRoleIsStrictlyNarrowerThanEveryReplacedModuleRole() {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();

        assertThatCode(() -> CompositeRoleNarrownessVerifier.verify(matrix))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsASchemaWideCompositeGrant() {
        GrantMatrix matrix = new GrantMatrixLoader().loadDefault();
        List<GrantMatrix.ObjectGrant> grants = new ArrayList<>(matrix.objectGrants());
        grants.add(
                new GrantMatrix.ObjectGrant(
                        "app_txn_examentry",
                        GrantMatrix.ObjectType.ALL_TABLES_IN_SCHEMA,
                        "delivery",
                        "",
                        List.of(GrantMatrix.Privilege.SELECT)));
        GrantMatrix widened =
                new GrantMatrix(
                        matrix.formatVersion(),
                        matrix.schemas(),
                        matrix.roles(),
                        matrix.memberships(),
                        grants,
                        matrix.defaultPrivileges(),
                        matrix.denials());

        assertThatThrownBy(() -> CompositeRoleNarrownessVerifier.verify(widened))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPOSITE_ROLE_NOT_NARROW");
    }
}
