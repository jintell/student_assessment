package org.meldtech.platform.platform.infra.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class FeatureDeliveryStatus {
    private FeatureDeliveryStatus() {}

    static Set<String> load() {
        try (InputStream input =
                new ClassPathResource("contracts/feature-delivery-status.yaml").getInputStream()) {
            return read(input);
        } catch (IOException exception) {
            throw RuntimeRoleManifest.invalid("feature delivery status unavailable");
        }
    }

    static Set<String> read(InputStream input) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object document;
        try {
            document = new Yaml(new SafeConstructor(options)).load(input);
        } catch (RuntimeException exception) {
            throw RuntimeRoleManifest.invalid("malformed feature delivery status");
        }
        if (!(document instanceof Map<?, ?> fields)
                || fields.size() != 1
                || !(fields.get("shippedFeatures") instanceof List<?> features)) {
            throw RuntimeRoleManifest.invalid("feature delivery status requires shippedFeatures");
        }
        Set<String> shipped = new HashSet<>();
        for (Object feature : features) {
            if (!(feature instanceof String name)
                    || !name.matches("FEAT-[A-Z]+-[0-9]{3}")
                    || !shipped.add(name)) {
                throw RuntimeRoleManifest.invalid("invalid or duplicate shipped feature");
            }
        }
        return Set.copyOf(shipped);
    }
}
