package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResultQueueConsumerTest {

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusPgRepository pgRepository = mock(TaskStatusPgRepository.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ResultQueueConsumer consumer = new ResultQueueConsumer(
            mock(org.springframework.data.redis.core.RedisTemplate.class),
            taskStatusRepository, pgRepository, pipelineMetrics, objectMapper);

    private TaskStatus queued(String taskId) {
        return new TaskStatus(taskId, AgentStep.JUDGING, false, 0, null,
                LocalDateTime.of(2026, 8, 3, 12, 0), "https://github.com/phdcoco/QuickByte_Demo", "main", "d1c5c5e", null);
    }

    @Test
    void successMessageMapsToPassed() {
        when(taskStatusRepository.findById("t1")).thenReturn(Optional.of(queued("t1")));

        consumer.process(Map.of(
                "request_id", "t1",
                "status", "success",
                "judge_report", Map.of("verdict", "pass")
        ));

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(captor.capture());
        TaskStatus saved = captor.getValue();

        assertThat(saved.currentAgent()).isEqualTo(AgentStep.PASSED);
        assertThat(saved.executable()).isTrue();
        assertThat(saved.errorMessage()).isNull();
        assertThat(saved.aiReports()).containsKey("judge_report");
        verify(pgRepository).upsert(saved);
    }

    @Test
    void failMessageMapsToFailed() {
        when(taskStatusRepository.findById("t2")).thenReturn(Optional.of(queued("t2")));

        consumer.process(Map.of(
                "request_id", "t2",
                "status", "fail",
                "critic_feedback", Map.of("cause", "smoke test failed")
        ));

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(captor.capture());
        TaskStatus saved = captor.getValue();

        assertThat(saved.currentAgent()).isEqualTo(AgentStep.FAILED);
        assertThat(saved.executable()).isFalse();
        assertThat(saved.errorMessage()).contains("fail");
        assertThat(saved.aiReports()).containsKey("critic_feedback");
    }

    @Test
    void infraErrorMapsToErrorNotFailed() {
        when(taskStatusRepository.findById("t3")).thenReturn(Optional.of(queued("t3")));

        consumer.process(Map.of(
                "request_id", "t3",
                "status", "infra_error"
        ));

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(captor.capture());
        TaskStatus saved = captor.getValue();

        // 인프라 오류는 코드 결함(FAILED)과 반드시 구분되어야 한다.
        assertThat(saved.currentAgent()).isEqualTo(AgentStep.ERROR);
        assertThat(saved.errorMessage()).contains("판정 불가");
    }
}
