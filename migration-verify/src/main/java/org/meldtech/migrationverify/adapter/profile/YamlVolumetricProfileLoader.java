package org.meldtech.migrationverify.adapter.profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.meldtech.migrationverify.core.VolumetricProfile;
import org.meldtech.migrationverify.port.VolumetricProfileLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlVolumetricProfileLoader implements VolumetricProfileLoader {

    @Override
    public VolumetricProfile load(Path profilePath) {
        try (InputStream input = Files.newInputStream(profilePath)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            Map<?, ?> root = objectMap(document, "profile");
            var tables = new ArrayList<VolumetricProfile.TableProfile>();
            for (Object value : objectList(required(root, "tables"), "tables")) {
                tables.add(table(objectMap(value, "table")));
            }
            return new VolumetricProfile(
                    integer(root, "schemaVersion"),
                    string(root, "profileVersion"),
                    number(root, "defaultSeed").longValue(),
                    integer(root, "scale"),
                    integerMap(required(root, "workload"), "workload"),
                    tables);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Cannot read volumetric profile: " + profilePath, exception);
        }
    }

    private static VolumetricProfile.TableProfile table(Map<?, ?> value) {
        Map<?, ?> primaryKey = objectMap(required(value, "primaryKey"), "primaryKey");
        return new VolumetricProfile.TableProfile(
                string(value, "name"),
                bool(value, "expectedToExist"),
                string(value, "rowCount"),
                new VolumetricProfile.PrimaryKeyProfile(
                        stringList(required(primaryKey, "columns"), "primaryKey.columns"),
                        string(primaryKey, "strategy")),
                stringList(required(value, "parents"), "parents"),
                integer(value, "generationOrder"),
                stringMap(required(value, "columns"), "columns"),
                string(value, "distribution"));
    }

    private static Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing volumetric profile field: " + key);
        }
        return value;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Expected non-blank string: " + key);
        }
        return text;
    }

    private static Number number(Map<?, ?> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected number: " + key);
        }
        return number;
    }

    private static int integer(Map<?, ?> map, String key) {
        return number(map, key).intValue();
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = required(map, key);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("Expected boolean: " + key);
        }
        return booleanValue;
    }

    private static Map<?, ?> objectMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected object: " + field);
        }
        return map;
    }

    private static List<?> objectList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected list: " + field);
        }
        return list;
    }

    private static List<String> stringList(Object value, String field) {
        return objectList(value, field).stream()
                .map(
                        item -> {
                            if (!(item instanceof String text)) {
                                throw new IllegalArgumentException("Expected string in: " + field);
                            }
                            return text;
                        })
                .toList();
    }

    private static Map<String, String> stringMap(Object value, String field) {
        var result = new LinkedHashMap<String, String>();
        objectMap(value, field)
                .forEach(
                        (key, item) -> {
                            if (!(key instanceof String name) || !(item instanceof String text)) {
                                throw new IllegalArgumentException("Expected string map: " + field);
                            }
                            result.put(name, text);
                        });
        return result;
    }

    private static Map<String, Integer> integerMap(Object value, String field) {
        var result = new LinkedHashMap<String, Integer>();
        objectMap(value, field)
                .forEach(
                        (key, item) -> {
                            if (!(key instanceof String name) || !(item instanceof Number number)) {
                                throw new IllegalArgumentException(
                                        "Expected integer map: " + field);
                            }
                            result.put(name, number.intValue());
                        });
        return result;
    }
}
