package org.meldtech.migrationverify.adapter.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.meldtech.migrationverify.port.ExamCriticalRelationLookup;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlExamCriticalTableRegistry implements ExamCriticalRelationLookup {

    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    private final Set<String> relations;

    private YamlExamCriticalTableRegistry(Set<String> relations) {
        this.relations = Set.copyOf(relations);
    }

    public static YamlExamCriticalTableRegistry load(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(document instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("Exam-critical registry must be a YAML object");
            }
            if (!Integer.valueOf(1).equals(root.get("schemaVersion"))) {
                throw new IllegalArgumentException(
                        "Unsupported exam-critical registry schemaVersion");
            }
            if (!(root.get("relations") instanceof List<?> entries)) {
                throw new IllegalArgumentException(
                        "Exam-critical registry relations must be a list");
            }
            var relations = new LinkedHashSet<String>();
            for (Object entry : entries) {
                if (!(entry instanceof String relation)
                        || !QUALIFIED_NAME.matcher(relation).matches()) {
                    throw new IllegalArgumentException(
                            "Exam-critical relation must be lower-case and schema-qualified: "
                                    + entry);
                }
                if (!relations.add(relation)) {
                    throw new IllegalArgumentException(
                            "Duplicate exam-critical relation: " + relation);
                }
            }
            return new YamlExamCriticalTableRegistry(relations);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Cannot read exam-critical table registry: " + path, exception);
        }
    }

    @Override
    public boolean isCritical(String relation) {
        return relations.contains(relation.toLowerCase(Locale.ROOT));
    }

    public Set<String> relations() {
        return relations;
    }
}
