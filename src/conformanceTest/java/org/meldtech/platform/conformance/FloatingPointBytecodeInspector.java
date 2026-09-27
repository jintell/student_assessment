package org.meldtech.platform.conformance;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class FloatingPointBytecodeInspector {

    private FloatingPointBytecodeInspector() {}

    static void inspect(JavaClass javaClass, Set<String> violations) {
        String resourceName = javaClass.getName().replace('.', '/') + ".class";
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream bytecode = classLoader.getResourceAsStream(resourceName)) {
            if (bytecode == null) {
                throw new IllegalStateException("Cannot inspect class bytecode: " + resourceName);
            }
            new ClassReader(bytecode)
                    .accept(
                            new Inspector(javaClass.getName(), violations),
                            ClassReader.SKIP_FRAMES);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot inspect class bytecode: " + resourceName, exception);
        }
    }

    private static final class Inspector extends ClassVisitor {

        private final String className;
        private final Set<String> violations;

        private Inspector(String className, Set<String> violations) {
            super(Opcodes.ASM9);
            this.className = className;
            this.violations = violations;
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            if (containsFloatingPointDescriptor(descriptor)
                    || containsFloatingPointSignature(signature)
                    || value instanceof Float
                    || value instanceof Double) {
                reject("field " + name);
            }
            return new FieldVisitor(Opcodes.ASM9) {};
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if (containsFloatingPointDescriptor(descriptor)
                    || containsFloatingPointSignature(signature)) {
                reject("signature of " + name);
            }
            return new FloatingPointMethodVisitor(name);
        }

        private void reject(String location) {
            violations.add(
                    "R6 exact decimal violated: "
                            + className
                            + " uses binary floating point in "
                            + location
                            + "; use shared.kernel Decimal conventions.");
        }

        private final class FloatingPointMethodVisitor extends MethodVisitor {

            private final String methodName;

            private FloatingPointMethodVisitor(String methodName) {
                super(Opcodes.ASM9);
                this.methodName = methodName;
            }

            @Override
            public void visitInsn(int opcode) {
                if (isFloatingPointInstruction(opcode)) {
                    reject("bytecode of " + methodName);
                }
            }

            @Override
            public void visitVarInsn(int opcode, int variable) {
                if (opcode == Opcodes.FLOAD
                        || opcode == Opcodes.DLOAD
                        || opcode == Opcodes.FSTORE
                        || opcode == Opcodes.DSTORE) {
                    reject("local value in " + methodName);
                }
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Float || value instanceof Double) {
                    reject("literal in " + methodName);
                }
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                if (containsFloatingPointDescriptor(descriptor)) {
                    reject("field access " + owner.replace('/', '.') + "." + name);
                }
            }

            @Override
            public void visitMethodInsn(
                    int opcode, String owner, String name, String descriptor, boolean isInterface) {
                if (containsFloatingPointDescriptor(descriptor)) {
                    reject("call " + owner.replace('/', '.') + "." + name);
                }
            }

            @Override
            public void visitInvokeDynamicInsn(
                    String name,
                    String descriptor,
                    Handle bootstrapMethodHandle,
                    Object... arguments) {
                if (containsFloatingPointDescriptor(descriptor)) {
                    reject("dynamic call " + name);
                }
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                if (containsFloatingPointDescriptor(descriptor)) {
                    reject("array allocation in " + methodName);
                }
            }
        }
    }

    private static boolean containsFloatingPointSignature(String signature) {
        return signature != null
                && (signature.contains("Ljava/lang/Double;")
                        || signature.contains("Ljava/lang/Float;"));
    }

    private static boolean containsFloatingPointDescriptor(String descriptor) {
        if (descriptor.startsWith("(")) {
            if (containsFloatingPoint(Type.getReturnType(descriptor))) {
                return true;
            }
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                if (containsFloatingPoint(argument)) {
                    return true;
                }
            }
            return false;
        }
        return containsFloatingPoint(Type.getType(descriptor));
    }

    private static boolean containsFloatingPoint(Type type) {
        if (type.getSort() == Type.FLOAT || type.getSort() == Type.DOUBLE) {
            return true;
        }
        if (type.getSort() == Type.ARRAY) {
            return containsFloatingPoint(type.getElementType());
        }
        return type.getSort() == Type.OBJECT
                && (type.getInternalName().equals("java/lang/Float")
                        || type.getInternalName().equals("java/lang/Double"));
    }

    private static boolean isFloatingPointInstruction(int opcode) {
        return switch (opcode) {
            case Opcodes.FCONST_0,
                    Opcodes.FCONST_1,
                    Opcodes.FCONST_2,
                    Opcodes.DCONST_0,
                    Opcodes.DCONST_1,
                    Opcodes.FALOAD,
                    Opcodes.DALOAD,
                    Opcodes.FASTORE,
                    Opcodes.DASTORE,
                    Opcodes.FADD,
                    Opcodes.DADD,
                    Opcodes.FSUB,
                    Opcodes.DSUB,
                    Opcodes.FMUL,
                    Opcodes.DMUL,
                    Opcodes.FDIV,
                    Opcodes.DDIV,
                    Opcodes.FREM,
                    Opcodes.DREM,
                    Opcodes.FNEG,
                    Opcodes.DNEG,
                    Opcodes.I2F,
                    Opcodes.I2D,
                    Opcodes.L2F,
                    Opcodes.L2D,
                    Opcodes.F2I,
                    Opcodes.F2L,
                    Opcodes.F2D,
                    Opcodes.D2I,
                    Opcodes.D2L,
                    Opcodes.D2F,
                    Opcodes.FCMPL,
                    Opcodes.FCMPG,
                    Opcodes.DCMPL,
                    Opcodes.DCMPG,
                    Opcodes.FRETURN,
                    Opcodes.DRETURN ->
                    true;
            default -> false;
        };
    }
}
