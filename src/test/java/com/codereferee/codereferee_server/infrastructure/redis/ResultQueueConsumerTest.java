package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.TaskStatusHistoryRepository;
import com.codereferee.codereferee_server.domain.validation.TaskStatusRepository;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResultQueueConsumerTest {

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusHistoryRepository historyRepository = mock(TaskStatusHistoryRepository.class);
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(new SimpleMeterRegistry());
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ResultQueueConsumer consumer = new ResultQueueConsumer(
            mock(org.springframework.data.redis.core.RedisTemplate.class),
            taskStatusRepository, historyRepository, pipelineMetrics, objectMapper);

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
        verify(historyRepository).upsert(saved);
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
    @Test
    void progressEventUpdatesStepAndRound() {
        when(taskStatusRepository.findById("t4")).thenReturn(Optional.of(queued("t4")));

        consumer.process(Map.of(
                "type", "progress",
                "request_id", "t4",
                "step", "REFINING",
                "round", 2,
                "max_rounds", 3
        ));

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(captor.capture());
        TaskStatus saved = captor.getValue();

        assertThat(saved.currentAgent()).isEqualTo(AgentStep.REFINING);
        assertThat(saved.iterationCount()).isEqualTo(2);
        assertThat(saved.errorMessage()).isNull();
        verify(historyRepository).upsert(saved);
    }

    @Test
    void progressAfterTerminalIsIgnored() {
        TaskStatus done = new TaskStatus("t5", AgentStep.PASSED, true, 1, null,
                LocalDateTime.of(2026, 8, 3, 12, 0), "https://github.com/phdcoco/QuickByte_Demo", "main", "d1c5c5e", null);
        when(taskStatusRepository.findById("t5")).thenReturn(Optional.of(done));

        consumer.process(Map.of("type", "progress", "request_id", "t5", "step", "BASELINE"));

        // 확정된 결과는 늦게 온 progress로 되돌아가지 않는다
        verify(taskStatusRepository, never()).save(any());
        verify(historyRepository, never()).upsert(any());
    }

    @Test
    void unknownMessageTypeIsIgnored() {
        consumer.process(Map.of("type", "heartbeat", "request_id", "t6"));

        verify(taskStatusRepository, never()).save(any());
        verify(historyRepository, never()).upsert(any());
    }

    @Test
    void unknownStepProgressIsIgnored() {
        when(taskStatusRepository.findById("t7")).thenReturn(Optional.of(queued("t7")));

        consumer.process(Map.of("type", "progress", "request_id", "t7", "step", "DEPLOYING"));

        verify(taskStatusRepository, never()).save(any());
    }
}