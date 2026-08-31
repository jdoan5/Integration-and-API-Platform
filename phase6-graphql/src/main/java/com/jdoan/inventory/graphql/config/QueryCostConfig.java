package com.jdoan.inventory.graphql.config;

import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The limits Kong cannot enforce.
 *
 * THE RATE-LIMITING LESSON. Phase 3 put a 20-requests-per-minute limit on the
 * REST facade and it worked, because in REST every request costs roughly the
 * same. That assumption is what a gateway's request counter is really built on.
 *
 * GraphQL breaks it. `{ lowStock { sku } }` is one backend call.
 * `{ lowStock { product { stockLevels { quantity } } } }` is dozens. Both are
 * one HTTP POST to one URL, so Kong counts 1 for each and cannot tell them
 * apart - it would have to parse and understand the query body to know, and
 * that is the application's job, not the gateway's.
 *
 * So the limit moves into the server, and changes units: not requests per
 * minute, but complexity per query. Kong still does auth, still does coarse
 * throttling, still stamps the correlation ID. It just cannot do this.
 *
 * Both beans are picked up automatically - GraphQlAutoConfiguration takes an
 * ObjectProvider<Instrumentation> and adds every one it finds.
 */
@Configuration
public class QueryCostConfig {

    /**
     * Depth stops recursion. Introspection is exempt - see IntrospectionAwareLimits. Product -> stockLevels is the only nesting the
     * schema allows today, so 10 is generous; the point is that it is bounded
     * at all, before someone adds a cyclic field and a client finds it.
     */
    @Bean
    public Instrumentation maxDepthInstrumentation(
            @Value("${inventory.graphql.max-depth:10}") int maxDepth) {
        return new IntrospectionAwareLimits.Depth(maxDepth);
    }

    /**
     * Complexity is the one that matters: it counts FIELDS the query will
     * resolve, so a wide selection over a long list scores high even when it is
     * shallow. Rejection happens after validation and before execution, so a
     * refused query costs zero backend calls rather than being killed partway
     * through having already hammered the SOAP service.
     */
    @Bean
    public Instrumentation maxComplexityInstrumentation(
            @Value("${inventory.graphql.max-complexity:120}") int maxComplexity,
            @Value("${inventory.graphql.default-page-size:25}") int defaultPageSize) {
        return new IntrospectionAwareLimits.Complexity(
                maxComplexity, new ListAwareComplexityCalculator(defaultPageSize));
    }
}
