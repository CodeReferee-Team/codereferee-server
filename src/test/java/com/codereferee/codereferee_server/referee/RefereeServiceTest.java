package com.codereferee.codereferee_server.referee;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefereeServiceTest {

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final DraftTaskQueue draftTaskQueue = mock(DraftTaskQueue.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final AiCoreClient aiCoreClient = mock(AiCoreClient.class);
    private final RefereeService refereeService =
            new RefereeService(taskStatusRepository, draftTaskQueue, pipelineMetrics, aiCoreClient);

    @Test
    void submitStoresInitialStatusAndEnqueuesDraftTask() {
        RepositoryValidationRequest request = new RepositoryValidationRequest(
                "https://github.com/user/repo", "main", "abc123", null
        );
        String requestId = refereeService.submit(request);

        ArgumentCaptor<TaskStatus> statusCaptor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(statusCaptor.capture());
        TaskStatus status = statusCaptor.getValue();

        assertThat(status.taskId()).isEqualTo(requestId);
        assertThat(status.currentAgent()).isEqualTo(AgentStep.DRAFT);
        assertThat(status.executable()).isFalse();
        assertThat(status.iterationCount()).isZero();
        assertThat(status.repositoryUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(status.branch()).isEqualTo("main");
        assertThat(status.commitSha()).isEqualTo("abc123");

        ArgumentCaptor<DraftTaskMessage> draftCaptor = ArgumentCaptor.forClass(DraftTaskMessage.class);
        verify(draftTaskQueue).enqueue(draftCaptor.capture());
        DraftTaskMessage draftTask = draftCaptor.getValue();

        assertThat(draftTask.taskId()).isEqualTo(requestId);
        assertThat(draftTask.repositoryUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(draftTask.branch()).isEqualTo("main");
        assertThat(draftTask.commitSha()).isEqualTo("abc123");
        assertThat(draftTask.submittedAt()).isEqualTo(status.updatedAt());
    }

    @Test
    void getStatusReadsStoredState() {
        TaskStatus status = new TaskStatus("task-1", AgentStep.DRAFT, false, 0, null, null);
        when(taskStatusRepository.findById("task-1")).thenReturn(Optional.of(status));

        assertThat(refereeService.getStatus("task-1")).contains(status);
    }
}
