package org.meldtech.platform.shared.kernel.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TypedIdentifierTest {

    private static final String CANONICAL = "018f3f1e-7b2a-7cc5-98c4-2c11e17c4698";

    @Test
    void parsesOnlyCanonicalUuidText() {
        List<Function<String, ?>> parsers =
                List.of(
                        TenantId::parse,
                        CandidateId::parse,
                        AttemptId::parse,
                        AssessmentId::parse,
                        ExamSessionId::parse,
                        ResultId::parse,
                        CorrectionRequestId::parse,
                        OutboxEventId::parse);

        parsers.forEach(parser -> assertEquals(CANONICAL, parser.apply(CANONICAL).toString()));
        parsers.forEach(
                parser ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> parser.apply(" " + CANONICAL)));
        parsers.forEach(
                parser ->
                        assertThrows(
                                IllegalArgumentException.class, () -> parser.apply("invalid")));
    }

    @Test
    void generatedIdentifiersRequireUuidVersionSeven() {
        assertEquals(CANONICAL, TenantId.newId(() -> UUID.fromString(CANONICAL)).toString());
        assertThrows(IllegalArgumentException.class, () -> TenantId.newId(UUID::randomUUID));
    }

    @Test
    void concreteIdentifierTypesAreNotInterchangeable() {
        assertNotEquals(TenantId.parse(CANONICAL), CandidateId.parse(CANONICAL));
    }

    @Test
    void identifiersExposeNoRawUuidAccessor() {
        List<Class<?>> types =
                List.of(
                        TenantId.class,
                        CandidateId.class,
                        AttemptId.class,
                        AssessmentId.class,
                        ExamSessionId.class,
                        ResultId.class,
                        CorrectionRequestId.class,
                        OutboxEventId.class);

        for (Class<?> type : types) {
            long uuidAccessors =
                    List.of(type.getMethods()).stream()
                            .filter(method -> Modifier.isPublic(method.getModifiers()))
                            .filter(method -> method.getReturnType().equals(UUID.class))
                            .map(Method::getName)
                            .count();
            assertEquals(0, uuidAccessors, type.getSimpleName());
        }
    }
}
