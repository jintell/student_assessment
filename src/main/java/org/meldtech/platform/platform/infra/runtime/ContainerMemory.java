package org.meldtech.platform.platform.infra.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Reads the process's container cgroup, never host physical memory. */
final class ContainerMemory {
    private final Path usage;
    private final long limit;

    ContainerMemory(Path root) throws IOException {
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Cgroup root must be absolute");
        }
        boolean v2 = Files.exists(root.resolve("memory.max"));
        limit = read(root.resolve(v2 ? "memory.max" : "memory/memory.limit_in_bytes"));
        usage = root.resolve(v2 ? "memory.current" : "memory/memory.usage_in_bytes");
        if (limit <= 0 || limit >= Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("A finite container memory limit is required");
        }
        validateCaps(
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                Runtime.getRuntime().maxMemory(),
                limit);
        used();
    }

    long limit() {
        return limit;
    }

    long used() throws IOException {
        return read(usage);
    }

    static void validateCaps(List<String> arguments, long heapMax, long limit) {
        var heaps = arguments.stream().filter(arg -> arg.startsWith("-Xmx")).toList();
        var direct =
                arguments.stream()
                        .filter(arg -> arg.startsWith("-XX:MaxDirectMemorySize="))
                        .toList();
        if (heaps.size() != 1
                || direct.size() != 1
                || arguments.stream().anyMatch(arg -> arg.contains("RAMPercentage="))) {
            throw new IllegalArgumentException(
                    "Explicit, unambiguous heap and direct-memory caps are required");
        }
        long heap = bytes(heaps.getFirst().substring(4));
        long buffers = bytes(direct.getFirst().substring("-XX:MaxDirectMemorySize=".length()));
        if (heap <= 0
                || buffers <= 0
                || heapMax <= 0
                || Math.max(heap, heapMax) >= limit - buffers) {
            throw new IllegalArgumentException(
                    "JVM memory caps must leave native headroom within the container limit");
        }
    }

    private static long bytes(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        char suffix = normalized.charAt(normalized.length() - 1);
        long multiplier =
                switch (suffix) {
                    case 'k' -> 1024L;
                    case 'm' -> 1024L * 1024;
                    case 'g' -> 1024L * 1024 * 1024;
                    default -> 1L;
                };
        return Math.multiplyExact(
                Long.parseLong(
                        multiplier == 1
                                ? normalized
                                : normalized.substring(0, normalized.length() - 1)),
                multiplier);
    }

    private static long read(Path path) throws IOException {
        long value = Long.parseLong(Files.readString(path).strip());
        if (value < 0) {
            throw new IllegalArgumentException("Invalid cgroup memory reading");
        }
        return value;
    }
}
