package org.meldtech.platform.platform.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.meldtech.platform.shared.infra.web.RequestContextPropagation;
import org.meldtech.platform.shared.kernel.context.ActorContext;
import org.meldtech.platform.shared.kernel.context.ActorId;
import org.meldtech.platform.shared.kernel.context.CorrelationId;
import org.meldtech.platform.shared.kernel.context.SourceIp;
import org.meldtech.platform.shared.kernel.identity.TenantId;
import reactor.util.context.Context;

class OutboxContextCarrierTest {

    private static final String CORRELATION_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private final OutboxContextCarrier carrier = new OutboxContextCarrier();
    private final ActorContext actor =
            ActorContext.tenantWorkforce(
                    new ActorId("author-1"),
                    TenantId.parse("01950f47-6000-7000-8000-000000000001"),
                    CorrelationId.parse(CORRELATION_ID),
                    SourceIp.parse("127.0.0.1"));

    @Test
    void carriesAmbientCorrelationAndTraceHeaders() {
        Context context =
                new RequestContextPropagation()
                        .write(Context.empty(), actor)
                        .put(
                                OutboxContextCarrier.TRACEPARENT_KEY,
                                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .put(OutboxContextCarrier.TRACESTATE_KEY, "vendor=value");

        OutboxContextCarrier.CarriedContext carried = carrier.capture(context, actor);

        assertThat(carried.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(carried.traceparent()).isPresent();
        assertThat(carried.tracestate()).contains("vendor=value");
    }

    @Test
    void refusesMissingCorrelationContext() {
        assertThatIllegalStateException()
                .isThrownBy(() -> carrier.capture(Context.empty(), actor))
                .withMessage("OUTBOX_CORRELATION_CONTEXT_MISSING");
    }

    @Test
    void rejectsMalformedTraceParent() {
        Context context =
                new RequestContextPropagation()
                        .write(Context.empty(), actor)
                        .put(OutboxContextCarrier.TRACEPARENT_KEY, "bad");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> carrier.capture(context, actor))
                .withMessage("Invalid W3C traceparent");
    }
}
