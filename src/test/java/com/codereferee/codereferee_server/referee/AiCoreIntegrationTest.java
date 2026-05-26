package com.codereferee.codereferee_server.referee;

import com.codereferee.codereferee_server.config.AiCoreConfig;
import com.codereferee.codereferee_server.config.AiCoreProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AiCoreIntegrationTest {

    private static final RepositoryValidationRequest TEST_REQUEST =
            new RepositoryValidationRequest("https://github.com/test/repo", "main", "deadbeef", "req-test-1");

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final DraftTaskQueue draftTaskQueue = mock(DraftTaskQueue.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final AiCoreClient aiCoreClient = mock(AiCoreClient.class);
    private final RefereeService refereeService =
            new RefereeService(taskStatusRepository, draftTaskQueue, pipelineMetrics, aiCoreClient);

    // ── Test 1: AI core timeout ──────────────────────────────────────────────

    @Test
    void aiCoreTimeoutMarksTaskStatusAsFailed() {
        org.mockito.Mockito.when(aiCoreClient.validate(any()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        String requestId = refereeService.submit(TEST_REQUEST);

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository, times(2)).save(captor.capture());

        TaskStatus failed = captor.getAllValues().get(1);
        assertThat(failed.taskId()).isEqualTo(requestId);
        assertThat(failed.currentAgent()).isEqualTo(AgentStep.FAILED);
        assertThat(failed.errorMessage()).contains("timeout");
    }

    // ── Test 2: AI core returns unexpected JSON schema ───────────────────────

    @Test
    void aiCoreSchemaParseErrorLogsAndMarksTaskStatusAsFailed() {
        org.mockito.Mockito.when(aiCoreClient.validate(any()))
                .thenThrow(new HttpMessageConversionException("JSON parse error: unexpected field"));

        String requestId = refereeService.submit(TEST_REQUEST);

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository, times(2)).save(captor.capture());

        TaskStatus failed = captor.getAllValues().get(1);
        assertThat(failed.taskId()).isEqualTo(requestId);
        assertThat(failed.currentAgent()).isEqualTo(AgentStep.FAILED);
        assertThat(failed.errorMessage()).contains("parse error");
    }

    // ── Test 3: real connectivity against the live AI core ───────────────────
    // Requires AI core to be running at http://127.0.0.1:8000
    // Run: ./gradlew test --tests "*.AiCoreIntegrationTest.realAiCoreConnectivityCheck"

    @Test
    void realAiCoreConnectivityCheck() {
        var properties = new AiCoreProperties("http://127.0.0.1:8000", "/v1/validations/repository");
        var realClient = new AiCoreClient(new AiCoreConfig().aiCoreRestClient(properties), properties);

        try {
            RepositoryValidationResponse response = realClient.validate(TEST_REQUEST);
            assertThat(response).isNotNull();
        } catch (HttpStatusCodeException e) {
            // 4xx/5xx: server is reachable and responded — connection path is healthy
            assertThat(e.getStatusCode().is4xxClientError() || e.getStatusCode().is5xxServerError()).isTrue();
        } catch (ResourceAccessException e) {
            org.junit.jupiter.api.Assertions.fail(
                    "AI core not reachable at http://127.0.0.1:8000 — start the server first. Error: " + e.getMessage()
            );
        }
    }
}
