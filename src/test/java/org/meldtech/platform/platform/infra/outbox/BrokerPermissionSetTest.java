package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BrokerPermissionSetTest {

    private static final Path PERMISSION_TEMPLATE =
            Path.of("deploy/rabbitmq/outbox-relay-permissions.json.template");

    @Test
    void relayUserCanOperateOnlyIntegrationTopology() throws IOException {
        JsonNode permission = permission();

        for (String capability : new String[] {"configure", "write", "read"}) {
            Pattern allowed = Pattern.compile(permission.path(capability).stringValue());
            assertThat(allowed.matcher("integration").matches()).isTrue();
            assertThat(allowed.matcher("integration.iam").matches()).isTrue();
            assertThat(allowed.matcher("integration.dlq").matches()).isTrue();
            assertThat(allowed.matcher("grading.requests").matches()).isFalse();
            assertThat(allowed.matcher("amq.gen-unowned").matches()).isFalse();
            assertThat(allowed.matcher("request.commands").matches()).isFalse();
        }
    }

    @Test
    void permissionSetReferencesDedicatedExternalIdentityWithoutEmbeddingCredential()
            throws IOException {
        String template = Files.readString(PERMISSION_TEMPLATE);
        JsonNode permission = permission();

        assertThat(permission.path("user").stringValue()).isEqualTo("relay-user");
        assertThat(permission.path("vhost").stringValue()).isEqualTo("cbt-platform");
        assertThat(template).doesNotContain("password", "password_hash");
    }

    private JsonNode permission() throws IOException {
        String rendered =
                Files.readString(PERMISSION_TEMPLATE)
                        .replace("${CBT_BROKER_RELAY_USER}", "relay-user")
                        .replace("${CBT_BROKER_VHOST}", "cbt-platform");
        return new ObjectMapper().readTree(rendered).path("permissions").get(0);
    }
}
