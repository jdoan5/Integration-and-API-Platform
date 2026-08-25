package com.jdoan.inventory.rest.soapclient;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpUrlConnection;

/**
 * Copies the inbound X-Correlation-ID onto the outbound SOAP call.
 *
 * WHY THIS EXISTS: Kong generates a correlation id and passes it to the REST
 * facade, but a trace only survives a hop if someone deliberately forwards it.
 * Without this class the id died here, and "one trace across gateway -> REST ->
 * SOAP -> DB" was a claim the code did not actually honour.
 *
 * That is the usual way distributed tracing breaks: not with an error, but
 * with a silently truncated trace that nobody notices until an incident.
 */
public final class CorrelationIdPropagator {

    public static final String HEADER = "X-Correlation-ID";

    private CorrelationIdPropagator() {}

    /** @return the id from the current HTTP request, or null outside one. */
    public static String currentId() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader(HEADER);
        }
        return null;
    }

    /**
     * A callback that stamps the id on the outgoing SOAP HTTP request.
     *
     * The header goes on the HTTP TRANSPORT, not into the SOAP envelope. It is
     * metadata about the call, not part of the business contract - putting it
     * in the payload would mean changing the XSD, and every consumer with it.
     */
    public static WebServiceMessageCallback propagate() {
        String id = currentId();
        return (WebServiceMessage message) -> {
            if (id == null || id.isBlank()) return;
            TransportContext context = TransportContextHolder.getTransportContext();
            if (context != null && context.getConnection() instanceof HttpUrlConnection connection) {
                connection.getConnection().setRequestProperty(HEADER, id);
            }
        };
    }
}
