package org.meldtech.platform.platform.infra.idempotency;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.meldtech.platform.shared.kernel.idempotency.IdempotencyStore;
import org.meldtech.platform.shared.kernel.idempotency.ReservationToken;
import org.meldtech.platform.shared.kernel.idempotency.StoredResponse;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class IdempotencyResponseCapture extends ServerHttpResponseDecorator {

    private final ReservationToken reservation;
    private final IdempotencyStore store;
    private final AtomicBoolean completed = new AtomicBoolean();

    IdempotencyResponseCapture(
            ServerHttpResponse delegate, ReservationToken reservation, IdempotencyStore store) {
        super(delegate);
        this.reservation = reservation;
        this.store = store;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return DataBufferUtils.join(Flux.from(body))
                .map(IdempotencyResponseCapture::readAndRelease)
                .defaultIfEmpty(new byte[0])
                .flatMap(this::completeAndWrite);
    }

    @Override
    public Mono<Void> setComplete() {
        return completeAndWrite(new byte[0]);
    }

    private Mono<Void> completeAndWrite(byte[] body) {
        if (!completed.compareAndSet(false, true)) {
            return Mono.empty();
        }
        HttpStatusCode status = getStatusCode();
        int statusValue = status == null ? 200 : status.value();
        String contentType =
                Optional.ofNullable(getHeaders().getContentType())
                        .map(Object::toString)
                        .orElse("application/octet-stream");
        StoredResponse response =
                new StoredResponse(statusValue, replayHeaders(getHeaders()), contentType, body);
        return Mono.from(store.complete(reservation, response))
                .then(
                        body.length == 0
                                ? super.setComplete()
                                : super.writeWith(Mono.just(bufferFactory().wrap(body))));
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        byte[] body = new byte[buffer.readableByteCount()];
        buffer.read(body);
        DataBufferUtils.release(buffer);
        return body;
    }

    private static Map<String, String> replayHeaders(HttpHeaders headers) {
        Map<String, String> replay = new LinkedHashMap<>();
        copy(headers, replay, HttpHeaders.LOCATION);
        copy(headers, replay, HttpHeaders.RETRY_AFTER);
        return Map.copyOf(replay);
    }

    private static void copy(HttpHeaders source, Map<String, String> target, String name) {
        String value = source.getFirst(name);
        if (value != null) {
            target.put(name, value);
        }
    }
}
