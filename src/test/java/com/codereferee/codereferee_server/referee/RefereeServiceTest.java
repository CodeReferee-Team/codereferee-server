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
    private final RefereeService refereeService =
            new RefereeService(taskStatusRepository, draftTaskQueue, pipelineMetrics);

    @Test
    void submitStoresInitialStatusAndEnqueuesDraftTask() {
        String taskId = refereeService.submit("Build a Python async API");

        ArgumentCaptor<TaskStatus> statusCaptor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(statusCaptor.capture());
        TaskStatus status = statusCaptor.getValue();

        assertThat(status.taskId()).isEqualTo(taskId);
        assertThat(status.currentAgent()).isEqualTo(AgentStep.DRAFT);
        assertThat(status.executable()).isFalse();
        assertThat(status.iterationCount()).isZero();

        ArgumentCaptor<DraftTaskMessage> draftCaptor = ArgumentCaptor.forClass(DraftTaskMessage.class);
        verify(draftTaskQueue).enqueue(draftCaptor.capture());
        DraftTaskMessage draftTask = draftCaptor.getValue();

        assertThat(draftTask.taskId()).isEqualTo(taskId);
        assertThat(draftTask.requirements()).isEqualTo("Build a Python async API");
        assertThat(draftTask.submittedAt()).isEqualTo(status.updatedAt());
    }

    @Test
    void getStatusReadsStoredState() {
        TaskStatus status = new TaskStatus("task-1", AgentStep.DRAFT, false, 0, null, null);
        when(taskStatusRepository.findById("task-1")).thenReturn(Optional.of(status));

        assertThat(refereeService.getStatus("task-1")).contains(status);
    }
}
