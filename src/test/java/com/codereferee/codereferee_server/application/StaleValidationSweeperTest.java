package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.TaskStatusHistoryRepository;
import com.codereferee.codereferee_server.domain.validation.TaskStatusRepository;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class StaleValidationSweeperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    private final TaskStatusRepository taskStatusRepository = mock(TaskStatusRepository.class);
    private final TaskStatusHistoryRepository historyRepository = mock(TaskStatusHistoryRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics pipelineMetrics = new PipelineMetrics(registry);
    private final ValidationTimeoutProperties timeouts =
            new ValidationTimeoutProperties(Duration.ofMinutes(60), Duration.ofMinutes(30));
    private final StaleValidationSweeper sweeper =
            new StaleValidationSweeper(taskStatusRepository, historyRepository, pipelineMetrics, timeouts);

    private TaskStatus stuck(String taskId, AgentStep step) {
        return new TaskStatus(taskId, step, false, 0, null,
                NOW.minusHours(3), NOW.minusHours(2),
                "https://github.com/phdcoco/QuickByte_Demo", "main", "d1c5c5e", null, ChaosOptions.NONE, null);
    }

    @Test
    void stuckRequestBecomesErrorNotFailed() {
        when(historyRepository.findStale(any(), any(), anyInt())).thenReturn(List.of(stuck("t1", AgentStep.BASELINE)));

        assertThat(sweeper.sweepAt(NOW)).isEqualTo(1);

        ArgumentCaptor<TaskStatus> captor = ArgumentCaptor.forClass(TaskStatus.class);
        verify(taskStatusRepository).save(captor.capture());
        TaskStatus saved = captor.getValue();

        // 사용자 코드를 판정한 결과가 아니라 판정 자체가 이뤄지지 않은 것이므로 ERROR다.
        assertThat(saved.currentAgent()).isEqualTo(AgentStep.ERROR);
        assertThat(saved.errorMessage()).contains("임계 시간을 초과");
        verify(historyRepository).upsert(saved);
    }

    /**
     * 아직 아무것도 정리하지 않았어도 시계열이 0으로 있어야 한다. 없으면 대시보드에
     * "No data"가 떠서 "지표 수집이 안 된다"와 "정리할 게 없다"가 구분되지 않는다.
     */
    @Test
    void sweptCountersExistAtZeroBeforeAnythingIsSwept() {
        for (AgentStep step : AgentStep.values()) {
            var counter = registry.find("codereferee.sweeper.swept").tag("from", step.name()).counter();
            if (step.isTerminal()) {
                // 스위퍼는 비종결 상태만 정리하므로 종결 상태 태그는 만들지 않는다.
                assertThat(counter).as("%s", step).isNull();
            } else {
                assertThat(counter).as("%s", step).isNotNull();
                assertThat(counter.count()).isZero();
            }
        }
    }

    /** 판정 카운터도 같은 이유로 0부터 존재해야 한다. */
    @Test
    void verdictCountersExistAtZeroBeforeAnyVerdict() {
        for (AgentStep step : List.of(AgentStep.PASSED, AgentStep.FAILED, AgentStep.ERROR)) {
            var counter = registry.find("codereferee.verdicts").tag("result", step.name()).counter();
            assertThat(counter).as("%s", step).isNotNull();
            assertThat(counter.count()).isZero();
        }
    }

    /**
     * 정리 건수를 떠난 단계와 함께 남겨야 어디서 응답이 끊기는지 보인다.
     * QUEUED에서 끊기면 대기열 적체, 그 외에서 끊기면 실행 중 유실이다.
     */
    @Test
    void recordsSweptCountTaggedWithTheStageItLeft() {
        when(historyRepository.findStale(any(), any(), anyInt()))
                .thenReturn(List.of(stuck("t1", AgentStep.BASELINE), stuck("t2", AgentStep.QUEUED)));

        sweeper.sweepAt(NOW);

        assertThat(registry.find("codereferee.sweeper.swept").tag("from", "BASELINE").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("codereferee.sweeper.swept").tag("from", "QUEUED").counter().count())
                .isEqualTo(1.0);
    }

    /** 스위퍼가 확정한 ERROR도 소요시간에 들어가야 한다. 타임아웃이 가장 긴 경로다. */
    @Test
    void recordsElapsedTimeOfSweptRequests() {
        when(historyRepository.findStale(any(), any(), anyInt()))
                .thenReturn(List.of(stuck("t1", AgentStep.BASELINE)));

        sweeper.sweepAt(NOW);

        var timer = registry.find("codereferee.validation.duration").tag("result", "ERROR").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        // 3시간 전 접수된 요청이므로 3시간 이상이다.
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.HOURS)).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void queuedGetsALongerGraceThanRunningStages() {
        when(historyRepository.findStale(any(), any(), anyInt())).thenReturn(List.of());

        sweeper.sweepAt(NOW);

        ArgumentCaptor<LocalDateTime> queuedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> runningBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(historyRepository).findStale(queuedBefore.capture(), runningBefore.capture(), anyInt());

        // 대기열은 샌드박스가 동시 1건만 처리해 길어질 수 있어 더 느슨해야 한다.
        assertThat(queuedBefore.getValue()).isEqualTo(NOW.minusMinutes(60));
        assertThat(runningBefore.getValue()).isEqualTo(NOW.minusMinutes(30));
        assertThat(queuedBefore.getValue()).isBefore(runningBefore.getValue());
    }

    @Test
    void nothingStaleMeansNoWrites() {
        when(historyRepository.findStale(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(sweeper.sweepAt(NOW)).isZero();

        verify(taskStatusRepository, never()).save(any());
        verify(historyRepository, never()).upsert(any());
    }

    @Test
    void defaultsApplyWhenPropertiesAreAbsent() {
        ValidationTimeoutProperties defaults = new ValidationTimeoutProperties(null, null);
        assertThat(defaults.queuedTimeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(defaults.runningTimeout()).isEqualTo(Duration.ofMinutes(30));
    }
}
