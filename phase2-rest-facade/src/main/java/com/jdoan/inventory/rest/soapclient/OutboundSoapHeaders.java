package com.jdoan.inventory.rest.soapclient;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpUrlConnection;

import java.net.HttpURLConnection;

/**
 * Stamps the outgoing SOAP call with the correlation id AND the trace context.
 *
 * WHY THIS EXISTS, AGAIN. Phase 2 already learned this lesson once: Kong
 * generates a correlation id, and a trace only survives a hop if somebody
 * deliberately forwards it. CorrelationIdPropagator was that fix.
 *
 * Phase 7 hit the identical wall with W3C trace context. Spring's observability
 * auto-instruments the servlet layer and RestClient/WebClient, so those hops
 * join up for free - but WebServiceTemplate is not on that list. The result was
 * exactly the failure the README already describes: not an error, just a trace
 * that stopped. Jaeger showed graphql-facade and soap-inventory-service both
 * reporting healthily, with DIFFERENT trace ids and one span each, which reads
 * as "everything is fine" until you go looking for the request that was slow.
 *
 * The header goes on the HTTP transport, not into the SOAP envelope - it is
 * metadata about the call, not part of the business contract. Putting it in the
 * payload would mean changing the XSD, and every consumer with it.
 */
@Component
public class OutboundSoapHeaders {

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboundSoapHeaders(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * A callback that stamps both headers on the outbound request.
     *
     * WebServiceTemplate takes a single callback, so the two concerns are
     * combined here rather than fighting over the one slot.
     */
    public WebServiceMessageCallback propagate() {
        // Read the ids on the CALLING thread. The callback runs later, and a
        // thread-local read at that point can easily belong to a different
        // thread, or to nobody.
        String correlationId = CorrelationIdPropagator.currentId();
        TraceContext traceContext = tracer.currentTraceContext().context();

        return (WebServiceMessage message) -> {
            TransportContext context = TransportContextHolder.getTransportContext();
            if (context == null || !(context.getConnection() instanceof HttpUrlConnection connection)) {
                return;
            }
            HttpURLConnection http = connection.getConnection();

            if (correlationId != null && !correlationId.isBlank()) {
                http.setRequestProperty(CorrelationIdPropagator.HEADER, correlationId);
            }
            if (traceContext != null) {
                // Writes `traceparent` (and `tracestate` when present), so the
                // receiving service continues this trace instead of starting one.
                propagator.inject(traceContext, http, HttpURLConnection::setRequestProperty);
            }
        };
    }
}
