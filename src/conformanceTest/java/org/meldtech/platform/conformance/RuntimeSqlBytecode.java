package org.meldtech.platform.conformance;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Uses ASM data flow so an unresolved SQL argument cannot hide a constructed lock name. */
final class RuntimeSqlBytecode {
    private static final Pattern LOCK_SQL =
            Pattern.compile("(?is)pg_(?:try_)?advisory_[a-z_]+|pg_locks");
    private static final String TICKS = RuntimeContainmentRule.RUNTIME + "RuntimeTickSources";
    private static final Set<String> SOURCE_METHODS =
            Set.of(
                    "sweep(Ljava/time/Duration;Lreactor/core/scheduler/Scheduler;)Lreactor/core/publisher/Flux;",
                    "memory(Lreactor/core/scheduler/Scheduler;)Lreactor/core/publisher/Flux;",
                    "whileHeld(Lorg/meldtech/platform/platform/api/SweepLock$Handle;"
                            + "Lreactor/core/publisher/Mono;)Lreactor/core/publisher/Mono;");

    private RuntimeSqlBytecode() {}

    static boolean containsLock(String sql) {
        return LOCK_SQL.matcher(sql.replace("\"", "")).find();
    }

    static void inspect(JavaClass type, Set<String> violations) {
        ClassNode node = read(type.getName().replace('.', '/'));
        boolean adapter = RuntimeContainmentRule.isLockAdapter(type.getName());
        node.fields.stream()
                .filter(field -> field.value instanceof String)
                .filter(field -> containsLock((String) field.value))
                .filter(field -> !adapter)
                .forEach(
                        field ->
                                violations.add(
                                        "RUNTIME_LOCK_SQL: " + type.getName() + "." + field.name));
        for (MethodNode method : node.methods) {
            inspectMethod(type.getName(), node, method, adapter, violations);
        }
        rejectDelayedRecursion(node, violations);
    }

    private static void inspectMethod(
            String className,
            ClassNode node,
            MethodNode method,
            boolean adapter,
            Set<String> violations) {
        String location = className + "." + method.name + method.desc;
        boolean source =
                className.equals(TICKS) && SOURCE_METHODS.contains(method.name + method.desc);
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
        } catch (AnalyzerException failure) {
            throw new IllegalStateException("Cannot inspect " + location, failure);
        }
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            if (instruction instanceof LdcInsnNode literal
                    && literal.cst instanceof String sql
                    && containsLock(sql)
                    && !adapter) {
                violations.add("RUNTIME_LOCK_SQL: " + location);
            }
            if (instruction instanceof MethodInsnNode call) {
                if (!source && periodic(call.owner, call.name, call.desc)) {
                    violations.add(
                            "RUNTIME_INTERVAL: "
                                    + location
                                    + " calls "
                                    + call.owner
                                    + "."
                                    + call.name);
                }
                int sqlIndex = sqlArgument(call.owner, call.name, call.desc);
                if (!adapter && sqlIndex >= 0 && frames[i] != null) {
                    Frame<SourceValue> frame = frames[i];
                    int argument =
                            frame.getStackSize()
                                    - Type.getArgumentTypes(call.desc).length
                                    + sqlIndex;
                    String sql =
                            constant(frame.getStack(argument), method, frames, new HashSet<>());
                    if (sql == null && !ReviewedSqlPlumbing.unchanged(className)) {
                        violations.add(
                                "RUNTIME_DYNAMIC_SQL: "
                                        + location
                                        + " has an uninspectable SQL argument");
                    } else if (sql != null && containsLock(sql)) {
                        violations.add("RUNTIME_LOCK_SQL: " + location);
                    }
                }
            }
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle
                            && !adapter
                            && sqlArgument(handle.getOwner(), handle.getName(), handle.getDesc())
                                    >= 0
                            && !ReviewedSqlPlumbing.unchanged(className)) {
                        violations.add(
                                "RUNTIME_DYNAMIC_SQL: "
                                        + location
                                        + " references a SQL execution method");
                    }
                    if (argument instanceof Handle handle
                            && !source
                            && periodic(handle.getOwner(), handle.getName(), handle.getDesc())) {
                        violations.add(
                                "RUNTIME_INTERVAL: "
                                        + location
                                        + " references "
                                        + handle.getName());
                    }
                }
            }
        }
    }

    private static boolean periodic(String owner, String name, String descriptor) {
        if (owner.equals("reactor/core/publisher/Flux") && name.equals("interval")) {
            return true;
        }
        if (Set.of(
                        "schedulePeriodically",
                        "scheduleAtFixedRate",
                        "scheduleWithFixedDelay",
                        "addFixedRateTask",
                        "addFixedDelayTask",
                        "addCronTask",
                        "setTaskScheduler")
                .contains(name)) {
            return true;
        }
        if (owner.equals("java/util/Timer") && name.startsWith("schedule")) {
            return true;
        }
        if (name.equals("schedule")
                && (owner.startsWith("reactor/core/scheduler/")
                        || owner.startsWith("java/util/concurrent/")
                        || owner.startsWith("org/springframework/scheduling/"))) {
            return true;
        }
        return owner.startsWith("reactor/core/publisher/")
                && (name.equals("repeatWhen")
                        || name.equals("repeatWhenEmpty")
                        || (name.equals("repeat") && !descriptor.startsWith("(J")));
    }

    private static int sqlArgument(String owner, String name, String descriptor) {
        boolean execution =
                Set.of(
                                        "sql",
                                        "createStatement",
                                        "createAuditStatement",
                                        "prepareStatement",
                                        "prepareCall",
                                        "execute",
                                        "executeQuery",
                                        "executeUpdate",
                                        "addBatch")
                                .contains(name)
                        || (owner.startsWith("org/springframework/jdbc/")
                                && Set.of(
                                                "query",
                                                "queryForObject",
                                                "queryForList",
                                                "update",
                                                "batchUpdate")
                                        .contains(name));
        if (!execution
                || !(owner.startsWith("org/springframework/r2dbc/")
                        || owner.startsWith("org/springframework/jdbc/")
                        || owner.startsWith("io/r2dbc/")
                        || owner.startsWith("java/sql/")
                        || owner.startsWith("org/meldtech/"))) {
            return -1;
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].getDescriptor().equals("Ljava/lang/String;")) {
                return i;
            }
        }
        return name.equals("sql") && arguments.length > 0 ? 0 : -1;
    }

    private static void rejectDelayedRecursion(ClassNode node, Set<String> violations) {
        Map<String, Set<String>> calls = new HashMap<>();
        Set<String> delayed = new HashSet<>();
        for (MethodNode method : node.methods) {
            String origin = method.name + method.desc;
            Set<String> targets = calls.computeIfAbsent(origin, ignored -> new HashSet<>());
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(node.name)) {
                        targets.add(call.name + call.desc);
                    }
                    if (call.owner.startsWith("reactor/core/publisher/")
                            && call.name.startsWith("delay")) {
                        delayed.add(origin);
                    }
                }
                if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (argument instanceof Handle handle
                                && handle.getOwner().equals(node.name)) {
                            targets.add(handle.getName() + handle.getDesc());
                        }
                    }
                }
            }
        }
        for (String origin : calls.keySet()) {
            Set<String> reachable = new HashSet<>();
            collectReachable(origin, calls, reachable);
            if (reachable.contains(origin) && reachable.stream().anyMatch(delayed::contains)) {
                violations.add(
                        "RUNTIME_INTERVAL: "
                                + node.name.replace('/', '.')
                                + "."
                                + origin
                                + " recursively schedules delayed work");
            }
        }
    }

    private static void collectReachable(
            String origin, Map<String, Set<String>> calls, Set<String> reachable) {
        for (String target : calls.getOrDefault(origin, Set.of())) {
            if (reachable.add(target)) {
                collectReachable(target, calls, reachable);
            }
        }
    }

    private static @Nullable String constant(
            SourceValue value,
            MethodNode method,
            Frame<SourceValue>[] frames,
            Set<AbstractInsnNode> visiting) {
        if (value.insns.size() != 1) {
            return null;
        }
        AbstractInsnNode instruction = value.insns.iterator().next();
        if (!visiting.add(instruction)) {
            return null;
        }
        try {
            int index = method.instructions.indexOf(instruction);
            Frame<SourceValue> frame = frames[index];
            if (instruction instanceof LdcInsnNode literal && literal.cst instanceof String sql) {
                return sql;
            }
            if (instruction instanceof VarInsnNode variable && frame != null) {
                return constant(
                        variable.getOpcode() == Opcodes.ALOAD
                                ? frame.getLocal(variable.var)
                                : frame.getStack(frame.getStackSize() - 1),
                        method,
                        frames,
                        visiting);
            }
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC) {
                return read(field.owner).fields.stream()
                        .filter(candidate -> candidate.name.equals(field.name))
                        .filter(candidate -> candidate.value instanceof String)
                        .map(candidate -> (String) candidate.value)
                        .findFirst()
                        .orElse(null);
            }
            if (instruction instanceof InvokeDynamicInsnNode dynamic
                    && frame != null
                    && dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                return concatenation(dynamic, frame, method, frames, visiting);
            }
            return null;
        } finally {
            visiting.remove(instruction);
        }
    }

    private static @Nullable String concatenation(
            InvokeDynamicInsnNode dynamic,
            Frame<SourceValue> frame,
            MethodNode method,
            Frame<SourceValue>[] frames,
            Set<AbstractInsnNode> visiting) {
        int argumentCount = Type.getArgumentTypes(dynamic.desc).length;
        StringBuilder result = new StringBuilder();
        int argument = frame.getStackSize() - argumentCount;
        int bootstrap = 1;
        String recipe =
                dynamic.bsmArgs.length == 0
                        ? "\u0001".repeat(argumentCount)
                        : (String) dynamic.bsmArgs[0];
        for (int i = 0; i < recipe.length(); i++) {
            char token = recipe.charAt(i);
            if (token == '\u0001') {
                String part = constant(frame.getStack(argument++), method, frames, visiting);
                if (part == null) {
                    return null;
                }
                result.append(part);
            } else if (token == '\u0002') {
                result.append(dynamic.bsmArgs[bootstrap++]);
            } else {
                result.append(token);
            }
        }
        return result.toString();
    }

    private static ClassNode read(String name) {
        try (InputStream input =
                Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream(name + ".class")) {
            if (input == null) {
                throw new IllegalStateException("Missing bytecode: " + name);
            }
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect " + name, failure);
        }
    }
}
