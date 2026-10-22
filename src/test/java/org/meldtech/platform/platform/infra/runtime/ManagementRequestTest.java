package org.meldtech.platform.platform.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class ManagementRequestTest {
    @Test
    void scrapeBypassesBusinessAdmissionOnlyOnTheManagementListener() {
        for (int port : new int[] {8080, 9091}) {
            var exchange =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.get("/prometheus")
                                    .localAddress(
                                            new InetSocketAddress(
                                                    InetAddress.getLoopbackAddress(), port))
                                    .build());
            assertThat(ManagementRequest.isProbeOrScrape(exchange)).isEqualTo(port == 9091);
        }
        assertThat(
                        ManagementRequest.isProbeOrScrape(
                                MockServerWebExchange.from(
                                        MockServerHttpRequest.get("/prometheus"))))
                .isFalse();
    }
}
