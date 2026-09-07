package org.meldtech.platform.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.api.TenantScopedQuery;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class R3SchemaOwnershipTests {

    private static final String BASE_PACKAGE = "org.meldtech.platform";
    private static final List<String> SQL_PREFIXES =
            List.of("SELECT ", "WITH ", "INSERT ", "UPDATE ", "DELETE ", "MERGE ");

    @Test
    void tenantQueriesReferenceOnlyTheirOwningSchema() throws Exception {
        Iterable<JavaClass> classes =
                new ClassFileImporter().importPath(Path.of("build", "classes", "java", "main"));

        assertTenantQueriesReferenceOnlyTheirOwningSchema(classes);
    }

    @Test
    void rejectsAQueryNamingAForeignSchema() {
        Iterable<JavaClass> fixture =
                new ClassFileImporter()
                        .importPackages("org.meldtech.platform.platform.slice.r3fixture");

        AssertionError failure =
                assertThrows(
                        AssertionError.class,
                        () -> assertTenantQueriesReferenceOnlyTheirOwningSchema(fixture));

        assertTrue(String.valueOf(failure.getMessage()).contains("R3 schema ownership violated:"));
    }

    private static void assertTenantQueriesReferenceOnlyTheirOwningSchema(
            Iterable<JavaClass> classes) throws Exception {
        List<String> violations = new ArrayList<>();

        for (JavaClass javaClass : classes) {
            if (!isQueryType(javaClass)) {
                continue;
            }
            String owningModule = owningModule(javaClass);
            for (String sql : sqlConstants(javaClass)) {
                for (String schema : schemasIn(sql)) {
                    if (!schema.equalsIgnoreCase(owningModule)) {
                        violations.add(
                                "R3 schema ownership violated: "
                                        + javaClass.getName()
                                        + " references "
                                        + schema
                                        + " but module "
                                        + owningModule
                                        + " may reference only schema "
                                        + owningModule
                                        + ".");
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    @Test
    void parsesQualifiedPostgreSqlRelationsStructurally() throws JSQLParserException {
        assertEquals(
                Set.of("platform"),
                schemasIn(
                        "SELECT metadata FROM platform.conformance_metadata WHERE tenant_id = :tenant"));
    }

    private static boolean isQueryType(JavaClass javaClass) {
        return javaClass.getSimpleName().equals("Queries")
                || javaClass.isAssignableTo(TenantScopedQuery.class);
    }

    private static String owningModule(JavaClass javaClass) {
        String relative = javaClass.getPackageName().substring((BASE_PACKAGE + ".").length());
        int separator = relative.indexOf('.');
        return separator < 0 ? relative : relative.substring(0, separator);
    }

    private static Set<String> sqlConstants(JavaClass javaClass) throws IOException {
        Set<String> constants = new HashSet<>();
        String resourceName = javaClass.getName().replace('.', '/') + ".class";
        ClassLoader classLoader =
                Objects.requireNonNull(Thread.currentThread().getContextClassLoader());
        try (InputStream input =
                Objects.requireNonNull(
                        classLoader.getResourceAsStream(resourceName),
                        () -> "Missing class resource " + resourceName)) {
            new ClassReader(input)
                    .accept(
                            new SqlConstantVisitor(constants),
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return constants;
    }

    private static Set<String> schemasIn(String sql) throws JSQLParserException {
        SchemaCollectingTablesFinder finder = new SchemaCollectingTablesFinder();
        for (Statement statement : CCJSqlParserUtil.parseStatements(sql)) {
            finder.getTables(statement);
        }
        return Set.copyOf(finder.schemas);
    }

    private static boolean looksLikeSql(String candidate) {
        String normalized = candidate.stripLeading().toUpperCase(Locale.ROOT);
        return SQL_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private static final class SqlConstantVisitor extends ClassVisitor {

        private final Set<String> constants;

        private SqlConstantVisitor(Set<String> constants) {
            super(Opcodes.ASM9);
            this.constants = constants;
        }

        @Override
        public @Nullable FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            addIfSql(value);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitLdcInsn(Object value) {
                    addIfSql(value);
                }
            };
        }

        private void addIfSql(Object value) {
            if (value instanceof String text && looksLikeSql(text)) {
                constants.add(text);
            }
        }
    }

    private static final class SchemaCollectingTablesFinder extends TablesNamesFinder<Void> {

        private final Set<String> schemas = new HashSet<>();

        @Override
        protected String extractTableName(Table table) {
            String schemaName = table.getSchemaName();
            if (schemaName != null && !schemaName.isBlank()) {
                schemas.add(schemaName.replace("\"", ""));
            }
            return super.extractTableName(table);
        }
    }
}
