package com.jdoan.inventory.orchestration.config;

import com.jdoan.inventory.orchestration.activity.InventoryActivities;
import com.jdoan.inventory.orchestration.workflow.StockTransferWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * The client and the worker, wired by hand.
 *
 * A deliberate choice, not a forced one. io.temporal:temporal-spring-boot-starter
 * 1.38.0 exists and supports Boot 4, and would do all of this by annotation.
 * (The abandoned artifact people find first is temporal-spring-boot-starter-alpha,
 * frozen at 1.23.2 since April 2024 - the -alpha suffix was dropped at 1.24.0.)
 *
 * These thirty lines are preferred here because they make the moving parts
 * visible - a stub to the service, a client on top of it, and a worker polling
 * one task queue - and because a single worker does not need auto-discovery.
 * With several workers across several queues, the starter is the better call.
 */
@Configuration
public class TemporalConfig {

    public static final String TASK_QUEUE = "stock-transfer";

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs);
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient client, InventoryActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        // The workflow is registered by CLASS and the activity by INSTANCE.
        // Temporal constructs a workflow object per execution and replays it,
        // so it cannot be a Spring bean with injected collaborators; activities
        // are ordinary objects and can be.
        worker.registerWorkflowImplementationTypes(StockTransferWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return factory;
    }

    @Bean
    public WorkerStarter workerStarter(WorkerFactory factory) {
        return new WorkerStarter(factory);
    }

    /**
     * Starts polling only once the application is actually up.
     *
     * Calling factory.start() inside the @Bean method would have the worker
     * accepting tasks while the rest of the context is still initialising, and
     * an activity that runs before its dependencies exist fails in a way that
     * looks like a Temporal problem rather than an ordering one.
     */
    public static final class WorkerStarter {
        private final WorkerFactory factory;

        WorkerStarter(WorkerFactory factory) {
            this.factory = factory;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            factory.start();
        }
    }
}
