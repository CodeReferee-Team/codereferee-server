package com.codereferee.codereferee_server.referee;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxResultListenerTest {

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusPgRepository pgRepository = mock(TaskStatusPgRepository.class);
    private final PipelineRouter pipelineRouter = new PipelineRouter();
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SandboxResultListener listener = new SandboxResultListener(
            taskStatusRepository,
            pgRepository,
            pipelineRouter,
            pipelineMetrics,
            objectMapper
    );

    @Test
    void sandboxSuccessRoutesTaskToJudge() {
        TaskStatus current = new TaskStatus(
                "task-1",
                AgentStep.DRAFT,
                false,
                0,
                null,
                LocalDateTime.of(2026, 5, 21, 16, 0)
        );
        when(taskStatusRepository.findById("task-1")).thenReturn(Optional.of(current));

        listener.onMessage(message("""
                {"taskId":"task-1","isExecutable":true,"errorMessage":null}
                """), null);

        TaskStatus routed = new TaskStatus(
                "task-1",
                AgentStep.JUDGE,
                true,
                0,
                null,
                current.updatedAt()
        );
        verify(taskStatusRepository).save(org.mockito.ArgumentMatchers.argThat(status ->
                status.taskId().equals(routed.taskId())
                        && status.currentAgent() == routed.currentAgent()
                        && status.executable()
                        && status.errorMessage() == null
        ));
        verify(pgRepository).upsert(org.mockito.ArgumentMatchers.argThat(status ->
                status.currentAgent() == AgentStep.JUDGE
        ));
    }

    @Test
    void sandboxFailureRoutesTaskToCritic() {
        TaskStatus current = new TaskStatus(
                "task-2",
                AgentStep.DRAFT,
                false,
                0,
                null,
                LocalDateTime.of(2026, 5, 21, 16, 0)
        );
        when(taskStatusRepository.findById("task-2")).thenReturn(Optional.of(current));

        listener.onMessage(message("""
                {"taskId":"task-2","isExecutable":false,"errorMessage":"Module missing"}
                """), null);

        verify(taskStatusRepository).save(org.mockito.ArgumentMatchers.argThat(status ->
                status.taskId().equals("task-2")
                        && status.currentAgent() == AgentStep.CRITIC
                        && !status.executable()
                        && status.errorMessage().equals("Module missing")
        ));
    }

    private DefaultMessage message(String json) {
        return new DefaultMessage("sandbox:result".getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8));
    }
}
