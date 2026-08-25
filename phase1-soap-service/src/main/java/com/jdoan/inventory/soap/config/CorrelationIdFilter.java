package com.jdoan.inventory.soap.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Receiving end of the distributed trace.
 *
 * Puts the incoming X-Correlation-ID into the SLF4J MDC, so EVERY log line
 * this request produces carries it - see the %X{correlationId} pattern in
 * application.properties. One grep then reconstructs a request's full journey
 * across Kong, the REST facade, and this service.
 *
 * If no id arrives (a direct call that bypassed the gateway) one is minted,
 * so a request is never untraceable.
 *
 * The MDC.remove() in the finally block is not optional: Tomcat reuses
 * threads, so a stale id would leak into the NEXT request on that thread and
 * quietly corrupt your traces.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = "local-" + UUID.randomUUID();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
