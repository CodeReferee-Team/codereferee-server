package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.TaskStatusHistoryRepository;
import com.codereferee.codereferee_server.domain.validation.TaskStatusRepository;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 소요시간·체류시간이 실제 파이프라인 흐름에서 기록되는지 본다. */
class ResultQueueConsumerMetricsTest {

    private static final LocalDateTime SUBMITTED = LocalDateTime.of(2026, 10, 1, 12, 0);

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusHistoryRepository historyRepository = mock(TaskStatusHistoryRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(registry);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @SuppressWarnings("unchecked")
    private final ResultQueueConsumer consumer = new ResultQueueConsumer(
            mock(RedisTemplate.class), taskStatusRepository, historyRepository,
            pipelineMetrics, objectMapper,
            mock(com.codereferee.codereferee_server.application.report.ReportDeliveryService.class));

    private Timer validationTimer(AgentStep verdict) {
        return registry.find("codereferee.validation.duration").tag("result", verdict.name()).timer();
    }

    private Timer stageTimer(AgentStep stage) {
        return registry.find("codereferee.stage.duration").tag("stage", stage.name()).timer();
    }

    private void given(TaskStatus status) {
        when(taskStatusRepository.findById(status.taskId())).thenReturn(Optional.of(status));
    }

    @Test
    void recordsEndToEndDurationOnTerminalVerdict() {
        given(new TaskStatus("t1", AgentStep.JUDGING, false, 0, null,
                SUBMITTED, SUBMITTED.plusMinutes(2),
                "https://github.com/phdcoco/QuickByte_Demo", "main", "sha", null, ChaosOptions.NONE, null));

        consumer.process(Map.of("request_id", "t1", "status", "success"));

        Timer timer = validationTimer(AgentStep.PASSED);
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        // 접수 시각부터 종결 시각(now)까지이므로 최소 2분이다.
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MINUTES)).isGreaterThanOrEqualTo(2.0);
    }

    /**
     * created_at 칼럼 추가 이전에 저장된 레코드는 접수 시각을 모른다. updatedAt으로 메우면
     * 소요시간이 0에 가깝게 집계되어 조용히 틀리므로, 아예 기록하지 않아야 한다.
     */
    @Test
    void skipsDurationWhenSubmissionTimeIsUnknown() {
        given(new TaskStatus("t2", AgentStep.JUDGING, false, 0, null,
                null, SUBMITTED.plusMinutes(2),
                "https://github.com/phdcoco/QuickByte_Demo", "main", "sha", null, ChaosOptions.NONE, null));

        consumer.process(Map.of("request_id", "t2", "status", "success"));

        assertThat(validationTimer(AgentStep.PASSED)).isNull();
        // 판정 자체는 정상 기록된다. 소요시간만 빠진다.
        assertThat(registry.find("codereferee.verdicts").tag("result", "PASSED").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordsDwellTimeOfTheStageBeingLeft() {
        given(new TaskStatus("t3", AgentStep.BASELINE, false, 0, null,
                SUBMITTED, SUBMITTED.plusMinutes(1),
                "https://github.com/phdcoco/QuickByte_Demo", "main", "sha", null, ChaosOptions.NONE, null));

        consumer.process(Map.of("type", "progress", "request_id", "t3", "step", "CHAOS"));

        Timer timer = stageTimer(AgentStep.BASELINE);
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        // 전이 대상(CHAOS)은 아직 떠나지 않았으므로 기록되지 않는다.
        assertThat(stageTimer(AgentStep.CHAOS)).isNull();
    }

    /** 같은 단계로 다시 온 progress는 전이가 아니므로 체류시간을 세지 않는다. */
    @Test
    void doesNotRecordDwellWhenStageIsUnchanged() {
        given(new TaskStatus("t4", AgentStep.BASELINE, false, 0, null,
                SUBMITTED, SUBMITTED.plusMinutes(1),
                "https://github.com/phdcoco/QuickByte_Demo", "main", "sha", null, ChaosOptions.NONE, null));

        consumer.process(Map.of("type", "progress", "request_id", "t4", "step", "BASELINE"));

        assertThat(stageTimer(AgentStep.BASELINE)).isNull();
    }
}
