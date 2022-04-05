// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.durabletask;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These integration tests are designed to exercise the core, high-level error-handling features of the Durable Task
 * programming model.
 * <p/>
 * These tests currently require a sidecar process to be running on the local machine (the sidecar is what accepts the
 * client operations and sends invocation instructions to the DurableTaskWorker).
 */
@Tag("integration")
public class ErrorHandlingIntegrationTests extends IntegrationTestBase {
    @Test
    void orchestratorException() {
        final String orchestratorName = "OrchestratorWithException";
        final String errorMessage = "Kah-BOOOOOM!!!";

        DurableTaskGrpcWorker worker = this.createWorkerBuilder()
                .addOrchestrator(orchestratorName, ctx -> {
                    throw new RuntimeException(errorMessage);
                })
                .buildAndStart();

        DurableTaskClient client = DurableTaskGrpcClient.newBuilder().build();
        try (worker; client) {
            String instanceId = client.scheduleNewOrchestrationInstance(orchestratorName, 0);
            OrchestrationMetadata instance = client.waitForInstanceCompletion(instanceId, defaultTimeout, true);
            assertNotNull(instance);
            assertEquals(OrchestrationRuntimeStatus.FAILED, instance.getRuntimeStatus());

            FailureDetails details = instance.getFailureDetails();
            assertNotNull(details);
            assertEquals("java.lang.RuntimeException", details.getErrorType());
            assertTrue(details.getErrorMessage().contains(errorMessage));
            assertNotNull(details.getStackTrace());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void activityException(boolean handleException) {
        final String orchestratorName = "OrchestratorWithActivityException";
        final String activityName = "Throw";
        final String errorMessage = "Kah-BOOOOOM!!!";

        DurableTaskGrpcWorker worker = this.createWorkerBuilder()
                .addOrchestrator(orchestratorName, ctx -> {
                    try {
                        ctx.callActivity(activityName).get();
                    } catch (TaskFailedException ex) {
                        if (handleException) {
                            ctx.complete("handled");
                        } else {
                            throw ex;
                        }
                    }
                })
                .addActivity(activityName, ctx -> {
                    throw new RuntimeException(errorMessage);
                })
                .buildAndStart();

        DurableTaskClient client = DurableTaskGrpcClient.newBuilder().build();
        try (worker; client) {
            String instanceId = client.scheduleNewOrchestrationInstance(orchestratorName, "");
            OrchestrationMetadata instance = client.waitForInstanceCompletion(instanceId, defaultTimeout, true);
            assertNotNull(instance);

            if (handleException) {
                String result = instance.readOutputAs(String.class);
                assertNotNull(result);
                assertEquals("handled", result);
            } else {
                assertEquals(OrchestrationRuntimeStatus.FAILED, instance.getRuntimeStatus());

                FailureDetails details = instance.getFailureDetails();
                assertNotNull(details);

                String expectedMessage = String.format(
                        "Task '%s' (#0) failed with an unhandled exception: %s",
                        activityName,
                        errorMessage);
                assertEquals(expectedMessage, details.getErrorMessage());
                assertEquals("com.microsoft.durabletask.TaskFailedException", details.getErrorType());
                assertNotNull(details.getStackTrace());
                // CONSIDER: Additional validation of getErrorDetails?
            }
        }
    }


    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10})
    public void retryActivityFailures(int maxNumberOfAttempts) {
        final String orchestratorName = "OrchestratorWithActivityException";
        final String activityName = "Throw";

        TaskOptions options = TaskOptions.fromRetryPolicy(RetryPolicy.newBuilder(
                maxNumberOfAttempts,
                Duration.ofMillis(1)).build());

        AtomicInteger actualAttemptCount = new AtomicInteger();
        DurableTaskGrpcWorker worker = this.createWorkerBuilder()
                .addOrchestrator(orchestratorName, ctx -> {
                    ctx.callActivity(activityName,null, options).get();
                })
                .addActivity(activityName, ctx -> {
                    actualAttemptCount.getAndIncrement();
                    throw new RuntimeException("Error #" + actualAttemptCount.get());
                })
                .buildAndStart();

        DurableTaskClient client = DurableTaskGrpcClient.newBuilder().build();
        try (worker; client) {
            String instanceId = client.scheduleNewOrchestrationInstance(orchestratorName, "");
            OrchestrationMetadata instance = client.waitForInstanceCompletion(instanceId, defaultTimeout, true);
            assertNotNull(instance);
            assertEquals(OrchestrationRuntimeStatus.FAILED, instance.getRuntimeStatus());

            // Make sure the exception details are still what we expect
            FailureDetails details = instance.getFailureDetails();
            assertNotNull(details);

            // Make sure the surfaced exception is the last one. This is reflected in both the task ID and the
            // error message. In the case of the task ID, it's going to be (N-1)*2 because there is a timer task
            // injected before each retry. This is useful to validate because changing this could break replays for
            // existing orchestrations that adopt an updated retry policy implementation (this has happened before).
            String expectedExceptionMessage = "Error #" + maxNumberOfAttempts;
            int expectedTaskId = (maxNumberOfAttempts - 1) * 2;
            String expectedMessage = String.format(
                    "Task '%s' (#%d) failed with an unhandled exception: %s",
                    activityName,
                    expectedTaskId,
                    expectedExceptionMessage);
            assertEquals(expectedMessage, details.getErrorMessage());
            assertEquals("com.microsoft.durabletask.TaskFailedException", details.getErrorType());
            assertNotNull(details.getStackTrace());

            // Confirm the number of attempts
            assertEquals(maxNumberOfAttempts, actualAttemptCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void subOrchestrationException(boolean handleException){
        final String orchestratorName = "OrchestrationWithBustedSubOrchestrator";
        final String subOrchestratorName = "BustedSubOrchestrator";
        final String errorMessage = "Kah-BOOOOOM!!!";

        DurableTaskGrpcWorker worker = this.createWorkerBuilder()
                .addOrchestrator(orchestratorName, ctx -> {
                    try {
                        String result = ctx.callSubOrchestrator(subOrchestratorName, "", String.class).get();
                        ctx.complete(result);
                    } catch (TaskFailedException ex) {
                        if (handleException) {
                            ctx.complete("handled");
                        } else {
                            throw ex;
                        }
                    }
                })
                .addOrchestrator(subOrchestratorName, ctx -> {
                    throw new RuntimeException(errorMessage);
                })
                .buildAndStart();
        DurableTaskClient client = DurableTaskGrpcClient.newBuilder().build();
        try (worker; client) {
            String instanceId = client.scheduleNewOrchestrationInstance(orchestratorName, 1);
            OrchestrationMetadata instance = client.waitForInstanceCompletion(instanceId, defaultTimeout, true);
            assertNotNull(instance);
            if (handleException) {
                assertEquals(OrchestrationRuntimeStatus.COMPLETED, instance.getRuntimeStatus());
                String result = instance.readOutputAs(String.class);
                assertNotNull(result);
                assertEquals("handled", result);
            } else {
                assertEquals(OrchestrationRuntimeStatus.FAILED, instance.getRuntimeStatus());
                FailureDetails details = instance.getFailureDetails();
                assertNotNull(details);
                String expectedMessage = String.format(
                        "Task '%s' (#0) failed with an unhandled exception: %s",
                        subOrchestratorName,
                        errorMessage);
                assertEquals(expectedMessage, details.getErrorMessage());
                assertEquals("com.microsoft.durabletask.TaskFailedException", details.getErrorType());
                assertNotNull(details.getStackTrace());
                // CONSIDER: Additional validation of getStackTrace?
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10})
    public void retrySubOrchestratorFailures(int maxNumberOfAttempts) {
        final String orchestratorName = "OrchestratorWithBustedSubOrchestrator";
        final String subOrchestratorName = "BustedSubOrchestrator";

        TaskOptions options = TaskOptions.fromRetryPolicy(RetryPolicy.newBuilder(
                maxNumberOfAttempts,
                Duration.ofMillis(1)).build());

        AtomicInteger actualAttemptCount = new AtomicInteger();
        DurableTaskGrpcWorker worker = this.createWorkerBuilder()
                .addOrchestrator(orchestratorName, ctx -> {
                    ctx.callSubOrchestrator(subOrchestratorName, null, null, options).get();
                })
                .addOrchestrator(subOrchestratorName, ctx -> {
                    actualAttemptCount.getAndIncrement();
                    throw new RuntimeException("Error #" + actualAttemptCount.get());
                })
                .buildAndStart();

        DurableTaskClient client = DurableTaskGrpcClient.newBuilder().build();
        try (worker; client) {
            String instanceId = client.scheduleNewOrchestrationInstance(orchestratorName, "");
            OrchestrationMetadata instance = client.waitForInstanceCompletion(instanceId, defaultTimeout, true);
            assertNotNull(instance);
            assertEquals(OrchestrationRuntimeStatus.FAILED, instance.getRuntimeStatus());

            // Make sure the exception details are still what we expect
            FailureDetails details = instance.getFailureDetails();
            assertNotNull(details);

            // Make sure the surfaced exception is the last one. This is reflected in both the task ID and the
            // error message. In the case of the task ID, it's going to be (N-1)*2 because there is a timer task
            // injected before each retry. This is useful to validate because changing this could break replays for
            // existing orchestrations that adopt an updated retry policy implementation (this has happened before).
            String expectedExceptionMessage = "Error #" + maxNumberOfAttempts;
            int expectedTaskId = (maxNumberOfAttempts - 1) * 2;
            String expectedMessage = String.format(
                    "Task '%s' (#%d) failed with an unhandled exception: %s",
                    subOrchestratorName,
                    expectedTaskId,
                    expectedExceptionMessage);
            assertEquals(expectedMessage, details.getErrorMessage());
            assertEquals("com.microsoft.durabletask.TaskFailedException", details.getErrorType());
            assertNotNull(details.getStackTrace());

            // Confirm the number of attempts
            assertEquals(maxNumberOfAttempts, actualAttemptCount.get());
        }
    }
}
