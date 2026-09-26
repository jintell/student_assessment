package org.meldtech.platform.platform.infra.kernel.error;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.meldtech.platform.platform.infra.idempotency.IdempotencyUnavailableException;
import org.meldtech.platform.shared.kernel.context.CorrelationIdGenerator;
import org.meldtech.platform.shared.kernel.error.ProblemCodeDefinition;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

@Configuration(proxyBeanMethods = false)
class KernelErrorConfiguration {

    @Bean
    ProblemDetailMetrics problemDetailMetrics() {
        return ProblemDetailMetrics.NOOP;
    }

    @Bean
    ProblemDetailMapper problemDetailMapper(
            ProblemDetailMetrics metrics, CorrelationIdGenerator correlationIds) {
        ErrorCatalogue source = loadCatalogue();
        Map<String, ProblemCodeDefinition> catalogue = new LinkedHashMap<>();
        source.problems()
                .forEach((code, definition) -> catalogue.put(code, toKernelDefinition(definition)));
        Map<Class<? extends Throwable>, String> mappings = new LinkedHashMap<>();
        mappings.put(IdempotencyUnavailableException.class, "CBT-PLAT-IDEMPOTENCY-UNAVAILABLE");
        mappings.put(AuthenticationException.class, "CBT-PLAT-UNAUTHORISED");
        mappings.put(AccessDeniedException.class, "CBT-PLAT-FORBIDDEN");
        mappings.put(IllegalArgumentException.class, "CBT-PLAT-VALIDATION");
        return new ProblemDetailMapper(catalogue, mappings, metrics, correlationIds);
    }

    private static ErrorCatalogue loadCatalogue() {
        ClassPathResource resource = new ClassPathResource("error-catalogue.yaml");
        try (InputStreamReader reader =
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return new ErrorCatalogueLoader().load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load the error catalogue", exception);
        }
    }

    private static ProblemCodeDefinition toKernelDefinition(ProblemDefinition source) {
        Map<String, ProblemCodeDefinition.ExtensionRule> extensions = new LinkedHashMap<>();
        source.extensions()
                .forEach(
                        (name, definition) ->
                                extensions.put(
                                        name,
                                        new ProblemCodeDefinition.ExtensionRule(
                                                ProblemCodeDefinition.PrimitiveType.valueOf(
                                                        definition
                                                                .type()
                                                                .toUpperCase(
                                                                        java.util.Locale.ROOT)),
                                                definition.required())));
        return new ProblemCodeDefinition(
                URI.create(source.type()),
                source.title(),
                source.status(),
                source.detail(),
                extensions);
    }
}
