package org.meldtech.platform.platform.infra.kernel.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.error.ProblemDetailDocument;
import org.meldtech.platform.shared.kernel.error.ProblemDetailMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
final class ProblemDetailWebExceptionHandler implements WebExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProblemDetailWebExceptionHandler.class);
    private static final String ERROR_CODE_KEY = "errorCode";

    private final ProblemDetailMapper mapper;
    private final ObjectMapper objectMapper;

    ProblemDetailWebExceptionHandler(ProblemDetailMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, @NonNull Throwable failure) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }
        return Mono.deferContextual(
                context -> {
                    Optional<String> correlationId =
                            context.<ActorContext>getOrEmpty(ActorContext.class)
                                    .map(actor -> actor.correlationId().toString());
                    ProblemDetailDocument problem =
                            mapper.mapSafely(
                                    failure,
                                    URI.create(exchange.getRequest().getPath().value()),
                                    correlationId);
                    logMappedFailure(problem, failure);
                    return write(exchange, problem);
                });
    }

    private static void logMappedFailure(ProblemDetailDocument problem, Throwable failure) {
        String previousErrorCode = MDC.get(ERROR_CODE_KEY);
        try {
            MDC.put(ERROR_CODE_KEY, problem.code());
            LOGGER.warn(
                    "Request failure mapped to {} ({})",
                    problem.code(),
                    failure.getClass().getName());
        } catch (RuntimeException ignored) {
            // Logging cannot change an error response.
        } finally {
            if (previousErrorCode == null) {
                MDC.remove(ERROR_CODE_KEY);
            } else {
                MDC.put(ERROR_CODE_KEY, previousErrorCode);
            }
        }
    }

    private Mono<Void> write(ServerWebExchange exchange, ProblemDetailDocument problem) {
        exchange.getResponse().setRawStatusCode(problem.status());
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        exchange.getResponse()
                .getHeaders()
                .set("X-Correlation-Id", problem.correlationId().toString());
        try {
            byte[] body = objectMapper.writeValueAsBytes(responseFields(problem));
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (JacksonException exception) {
            exchange.getResponse().setRawStatusCode(500);
            byte[] fallback =
                    mapper.renderMinimalFallback(problem.instance(), problem.correlationId());
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(fallback)));
        }
    }

    private static Map<String, Object> responseFields(ProblemDetailDocument problem) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", problem.type().toString());
        fields.put("title", problem.title());
        fields.put("status", problem.status());
        fields.put("code", problem.code());
        fields.put("detail", problem.detail());
        fields.put("instance", problem.instance().toString());
        fields.put("correlationId", problem.correlationId().toString());
        fields.putAll(problem.extensions());
        return fields;
    }
}
