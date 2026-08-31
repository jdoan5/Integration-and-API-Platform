package com.jdoan.inventory.graphql.error;

import com.jdoan.inventory.graphql.soapclient.UpstreamFaultException;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceIOException;

import java.util.Map;

/**
 * Turns backend failures into typed GraphQL errors.
 *
 * The Phase 1 lesson, restated in a third protocol. SOAP gave consumers a
 * schema-defined fault with a machine-readable code; REST gave them an HTTP
 * status. GraphQL always answers 200, so neither is available - the error has
 * to travel inside the response body, and a consumer branching on English prose
 * is exactly the failure mode the XSD was designed to prevent.
 *
 * So the upstream fault code goes into `extensions.code`, unchanged. Same
 * contract, third envelope.
 */
@Component
public class InventoryExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof UpstreamFaultException fault) {
            return GraphQLError.newError()
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(fault.getMessage())
                    .path(env.getExecutionStepInfo().getPath())
                    .location(env.getField().getSourceLocation())
                    .extensions(Map.of(
                            "code", fault.getCode(),
                            "field", fault.getField() == null ? "" : fault.getField()))
                    .build();
        }

        if (ex instanceof WebServiceIOException) {
            return GraphQLError.newError()
                    .errorType(ErrorType.INTERNAL_ERROR)
                    .message("The upstream inventory service is unreachable.")
                    .path(env.getExecutionStepInfo().getPath())
                    .location(env.getField().getSourceLocation())
                    .extensions(Map.of("code", "UPSTREAM_UNAVAILABLE"))
                    .build();
        }

        return null;   // let Spring's default handling deal with anything else
    }
}
