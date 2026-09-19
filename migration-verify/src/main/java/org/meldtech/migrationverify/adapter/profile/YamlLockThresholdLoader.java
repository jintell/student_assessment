package org.meldtech.migrationverify.adapter.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.meldtech.migrationverify.measure.LockThresholds;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlLockThresholdLoader {

    public LockThresholds load(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            Map<?, ?> root = map(document, "root");
            Map<?, ?> lockDuration = map(root.get("lockDuration"), "lockDuration");
            Map<?, ?> examCritical = map(lockDuration.get("examCritical"), "examCritical");
            Map<?, ?> nonCritical = map(lockDuration.get("nonCritical"), "nonCritical");
            return new LockThresholds(
                    number(examCritical, "warnMs"),
                    number(examCritical, "failMs"),
                    number(nonCritical, "failMs"));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read lock thresholds: " + path, exception);
        }
    }

    private static Map<?, ?> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected lock-threshold object: " + field);
        }
        return map;
    }

    private static long number(Map<?, ?> values, String field) {
        if (!(values.get(field) instanceof Number number)) {
            throw new IllegalArgumentException("Expected lock-threshold number: " + field);
        }
        return number.longValue();
    }
}
