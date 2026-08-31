package com.jdoan.inventory.graphql.config;

import graphql.ExecutionResult;
import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.Field;
import graphql.language.OperationDefinition;

/**
 * Query limits that do not apply to introspection.
 *
 * FOUND BY OPENING THE UI, not by any test. GraphiQL's first act is to send the
 * full introspection query, which is 15 levels deep and enormously wide -
 * it walks every type, its fields, their types, their fields' arguments, and so
 * on. A depth limit of 10 rejected it, so the schema browser loaded with
 * `maximum query depth exceeded 15 > 10` and no autocomplete, no docs panel,
 * and no way to explore the contract.
 *
 * The shallow introspection in test-graphql.sh passed happily, which is why the
 * suite was green while the developer-facing surface was broken. A schema that
 * cannot be introspected is a self-describing contract that does not describe
 * itself.
 *
 * Raising the limit past 15 would "fix" it and quietly gut the protection for
 * real queries. The right split is that these two things are different:
 *
 *   - Business queries are client-authored, unbounded in shape, and need a
 *     budget - that is what depth and complexity are for.
 *   - Introspection is ONE fixed query whose cost is a property of the schema,
 *     not of the caller. Scoring it against a business budget is a category
 *     error.
 *
 * Introspection has its own controls: graphql-java ships GoodFaithIntrospection
 * (on by default) to reject abusive variants, and a production deployment turns
 * introspection off entirely with
 * `spring.graphql.schema.introspection.enabled=false` rather than trying to
 * make a depth limit do the job.
 */
public final class IntrospectionAwareLimits {

    private IntrospectionAwareLimits() {}

    /**
     * True when every top-level selection is a meta-field (`__schema`, `__type`).
     *
     * Deliberately strict: a query that mixes introspection with real fields is
     * a business query and gets scored like one, so this cannot be used as a
     * way to smuggle an expensive selection past the budget.
     */
    static boolean isIntrospectionOnly(ExecutionContext context) {
        OperationDefinition operation = context.getOperationDefinition();
        if (operation == null || operation.getSelectionSet() == null) {
            return false;
        }
        return operation.getSelectionSet().getSelections().stream()
                .allMatch(selection -> selection instanceof Field field
                        && field.getName().startsWith("__"));
    }

    /** Depth limiting, skipped for introspection. */
    public static final class Depth extends MaxQueryDepthInstrumentation {

        public Depth(int maxDepth) {
            super(maxDepth);
        }

        @Override
        public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
            if (isIntrospectionOnly(parameters.getExecutionContext())) {
                return SimpleInstrumentationContext.noOp();
            }
            return super.beginExecuteOperation(parameters, state);
        }
    }

    /** Complexity limiting, skipped for introspection. */
    public static final class Complexity extends MaxQueryComplexityInstrumentation {

        public Complexity(int maxComplexity, FieldComplexityCalculator calculator) {
            super(maxComplexity, calculator);
        }

        @Override
        public InstrumentationContext<ExecutionResult> beginExecuteOperation(
                InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
            if (isIntrospectionOnly(parameters.getExecutionContext())) {
                return SimpleInstrumentationContext.noOp();
            }
            return super.beginExecuteOperation(parameters, state);
        }
    }
}
