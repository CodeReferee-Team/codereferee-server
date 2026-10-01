package com.codereferee.codereferee_server.application;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.TaskStatusHistoryRepository;
import com.codereferee.codereferee_server.domain.validation.TaskStatusRepository;
import com.codereferee.codereferee_server.infrastructure.metrics.PipelineMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결과 메시지가 끝내 도착하지 않은 요청을 ERROR로 확정한다.
 *
 * AI 워커가 통째로 죽거나 샌드박스 인스턴스가 기동하지 못하면 BE는 아무 메시지도 받지 못하고,
 * 그 요청은 영원히 비종결 상태로 남는다. AI 측의 timeout 통보 여부와 무관하게
 * BE가 자체적으로 가져야 할 방어선이다.
 *
 * 코드 결함(FAILED)이 아니라 ERROR인 이유는, 사용자 코드를 판정한 결과가 아니라
 * 판정 자체가 이뤄지지 않았기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaleValidationSweeper {

    /** 한 번에 정리할 최대 건수. 장애가 길어져 쌓였을 때 한 주기를 통째로 잡아먹지 않도록 제한한다. */
    private static final int BATCH_LIMIT = 100;

    private final TaskStatusRepository taskStatusRepository;
    private final TaskStatusHistoryRepository historyRepository;
    private final PipelineMetrics pipelineMetrics;
    private final ValidationTimeoutProperties timeouts;

    @Scheduled(fixedDelayString = "${codereferee.validation.sweep-interval:1m}")
    public void sweep() {
        sweepAt(LocalDateTime.now());
    }

    /** 테스트에서 시각을 고정할 수 있도록 분리. 정리한 건수를 돌려준다. */
    public int sweepAt(LocalDateTime now) {
        List<TaskStatus> stale = historyRepository.findStale(
                now.minus(timeouts.queuedTimeout()),
                now.minus(timeouts.runningTimeout()),
                BATCH_LIMIT);

        for (TaskStatus status : stale) {
            TaskStatus timedOut = status.withError(
                    "결과를 받지 못한 채 임계 시간을 초과했습니다 (마지막 갱신: " + status.updatedAt() + ")");
            taskStatusRepository.save(timedOut);
            historyRepository.upsert(timedOut);
            pipelineMetrics.recordTransition(status.currentAgent(), AgentStep.ERROR);
            pipelineMetrics.recordVerdict(AgentStep.ERROR);
            log.warn("[Sweeper] taskId={} {} → ERROR (마지막 갱신 {})",
                    status.taskId(), status.currentAgent(), status.updatedAt());
        }
        if (!stale.isEmpty()) {
            log.info("[Sweeper] 미결 요청 {}건을 ERROR로 확정했습니다", stale.size());
        }
        return stale.size();
    }
}
