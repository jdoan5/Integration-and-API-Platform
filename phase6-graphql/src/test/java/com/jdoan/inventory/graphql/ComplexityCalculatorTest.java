package com.jdoan.inventory.graphql;

import com.jdoan.inventory.graphql.config.ListAwareComplexityCalculator;
import graphql.analysis.FieldComplexityEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scoring that makes a complexity limit mean something.
 *
 * The default calculator scores a list the same as a single object, which is
 * how you end up with a limit that looks protective and is not. These assert
 * the multiplication actually happens.
 */
class ComplexityCalculatorTest {

    private static final ListAwareComplexityCalculator CALC =
            new ListAwareComplexityCalculator(25);

    private FieldComplexityEnvironment env(GraphQLFieldDefinition field, Map<String, Object> args) {
        return new FieldComplexityEnvironment(null, field, null, args, null);
    }

    private GraphQLFieldDefinition listField() {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("lowStock").type(GraphQLList.list(GraphQLString)).build();
    }

    private GraphQLFieldDefinition scalarField() {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("sku").type(GraphQLString).build();
    }

    @Test
    void aScalarFieldCostsOnePlusItsChildren() {
        assertEquals(4, CALC.calculate(env(scalarField(), Map.of()), 3));
    }

    @Test
    void aListFieldMultipliesItsChildrenByTheLimitArgument() {
        // 1 for the field itself, plus 5 elements x 2 child fields.
        assertEquals(11, CALC.calculate(env(listField(), Map.of("limit", 5)), 2));
    }

    @Test
    void withoutALimitArgumentItFallsBackToTheConfiguredPageSize() {
        assertEquals(51, CALC.calculate(env(listField(), Map.of()), 2));
    }

    @Test
    void aNonNullListIsStillRecognisedAsAList() {
        // [LowStockItem!]! is NonNull(List(...)) - unwrapping only the outer
        // NonNull is what makes this work, and forgetting to is how a list
        // silently scores as a scalar.
        GraphQLFieldDefinition field = GraphQLFieldDefinition.newFieldDefinition()
                .name("lowStock")
                .type(GraphQLNonNull.nonNull(GraphQLList.list(
                        GraphQLNonNull.nonNull(GraphQLObjectType.newObject().name("X").build()))))
                .build();
        assertTrue(CALC.calculate(env(field, Map.of("limit", 10)), 3) > 10);
    }

    @Test
    void aWideSelectionOverALongListScoresHigherThanADeepNarrowOne() {
        int wide = CALC.calculate(env(listField(), Map.of("limit", 50)), 4);
        int narrowButDeep = CALC.calculate(env(scalarField(), Map.of()), 20);
        assertTrue(wide > narrowButDeep,
                "the expensive query must score higher than the merely deep one");
    }
}
