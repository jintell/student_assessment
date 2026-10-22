package org.meldtech.platform.platform.infra.outbox;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class LocalNotificationReceiver {
    private LocalNotificationReceiver() {}

    static void start() throws Exception {
        List<String> receipts = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext(
                "/runbook",
                exchange -> {
                    byte[] body;
                    try (var input =
                            java.util.Objects.requireNonNull(
                                    LocalNotificationReceiver.class.getResourceAsStream(
                                            "/local-outbox-runbook.md"))) {
                        body = input.readAllBytes();
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.createContext(
                "/",
                exchange -> {
                    if (exchange.getRequestMethod().equals("POST")) {
                        String notification =
                                new String(
                                        exchange.getRequestBody().readNBytes(65536),
                                        StandardCharsets.UTF_8);
                        receipts.add(
                                "{\"receivedAt\":\""
                                        + Instant.now()
                                        + "\",\"notification\":"
                                        + notification
                                        + "}");
                    }
                    byte[] body =
                            ("[" + String.join(",", receipts) + "]")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
    }
}
