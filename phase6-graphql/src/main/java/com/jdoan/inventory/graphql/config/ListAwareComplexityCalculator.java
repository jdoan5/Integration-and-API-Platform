package com.jdoan.inventory.graphql.config;

import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.FieldComplexityEnvironment;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;

/**
 * Scores a query by how many fields it will actually resolve.
 *
 * WHY THE DEFAULT IS NOT ENOUGH - and this is the part most write-ups skip.
 * graphql-java's out-of-the-box calculator adds 1 per field in the DOCUMENT, so
 * it cannot tell `lowStock { sku }` returning one row from the same query
 * returning six hundred. Both score 2. A complexity limit built on that number
 * looks like protection and is not: the query that actually hurts you is a
 * *narrow* selection over a *long* list, and the default scores that as cheap.
 *
 * So list fields multiply their children by how many elements they are expected
 * to yield, taken from the `limit` argument where the schema has one.
 *
 * That number is an ESTIMATE and cannot be anything else - the true count is
 * only known after the backend answers, which is far too late to refuse the
 * query. Cost analysis is a bet placed before execution on how expensive
 * execution will be, and the honest version of this lesson is that you are
 * choosing how wrong you are willing to be, in which direction. Overestimating
 * rejects some legitimate queries; underestimating is how you get paged.
 */
public class ListAwareComplexityCalculator implements FieldComplexityCalculator {

    private final int defaultPageSize;

    public ListAwareComplexityCalculator(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    @Override
    public int calculate(FieldComplexityEnvironment env, int childComplexity) {
        GraphQLType type = GraphQLTypeUtil.unwrapNonNull(env.getFieldDefinition().getType());

        if (type instanceof GraphQLList) {
            return 1 + (expectedSize(env) * childComplexity);
        }
        return 1 + childComplexity;
    }

    /**
     * How many elements this list field is expected to return.
     *
     * A `limit` argument is the client telling us, so believe it. Without one -
     * `stockLevels` has no limit, it returns however many warehouses stock the
     * product - fall back to a configured guess. A schema where every list is
     * paginated would not need the guess, which is a decent argument for
     * requiring pagination on every list field.
     */
    private int expectedSize(FieldComplexityEnvironment env) {
        Object limit = env.getArguments().get("limit");
        if (limit instanceof Integer value && value > 0) {
            return value;
        }
        return defaultPageSize;
    }
}
