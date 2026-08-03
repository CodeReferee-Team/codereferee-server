package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 모듈의 최종 결과를 Redis List(BLPOP)로 소비한다.
 * pub/sub이 아닌 List를 쓰는 이유: pub/sub은 구독자가 죽어 있는 동안 도착한 메시지가 유실되기 때문.
 * List는 BE가 재시작해도 큐에 남아 있으므로 장애 시점의 리포트도 보존할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultQueueConsumer {

    public static final String QUEUE_KEY = "codereferee:workflow:output";
    private static final Duration POP_TIMEOUT = Duration.ofSeconds(5);

    private final RedisTemplate<String, Object> redisTemplate;
    private final TaskStatusRepository taskStatusRepository;
    private final TaskStatusPgRepository pgRepository;
    private final PipelineMetrics pipelineMetrics;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "result-queue-consumer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = false;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        executor.submit(this::consumeLoop);
        log.info("[ResultQueue] consumer started on key={}", QUEUE_KEY);
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void consumeLoop() {
        while (running) {
            try {
                Object raw = redisTemplate.opsForList().leftPop(QUEUE_KEY, POP_TIMEOUT);
                if (raw != null) {
                    process(raw);
                }
            } catch (Exception e) {
                if (!running) return;
                log.error("[ResultQueue] consume failed, retrying", e);
                sleepQuietly();
            }
        }
    }

    // 테스트에서 직접 호출할 수 있도록 패키지 가시성으로 분리
    void process(Object raw) {
        SandboxResultMessage msg = objectMapper.convertValue(raw, SandboxResultMessage.class);

        String taskId = msg.requestId() != null ? msg.requestId() : msg.jobId();
        log.info("[ResultQueue] received taskId={} status={}", taskId, msg.status());

        TaskStatus current = taskStatusRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("TaskStatus not found: " + taskId));

        AgentStep verdict = mapVerdict(msg.status());
        String errorMessage = switch (verdict) {
            case PASSED -> null;
            case ERROR -> "Pipeline error (판정 불가): " + msg.status();
            default -> "Validation failed with status: " + msg.status();
        };

        Map<String, Object> aiReports = buildAiReports(msg);
        boolean executable = verdict == AgentStep.PASSED;
        TaskStatus updated = current.withAiResult(verdict, executable, errorMessage, aiReports);

        pipelineMetrics.recordTransition(current.currentAgent(), verdict);
        pipelineMetrics.recordVerdict(verdict);

        taskStatusRepository.save(updated);
        pgRepository.upsert(updated);

        log.info("[ResultQueue] taskId={} → {} reports={}", taskId, verdict, aiReports.keySet());
    }

    /**
     * AI 모듈 status → 최종 상태 매핑.
     * - success               → PASSED
     * - error / infra_error   → ERROR (인프라 오류: Critic 루프를 타지 않았고 코드 판정이 아님)
     * - 그 외 (fail 등)        → FAILED (라운드 소진 포함, 코드에 대한 최종 실패 판정)
     */
    private AgentStep mapVerdict(String status) {
        if ("success".equals(status)) return AgentStep.PASSED;
        if ("error".equals(status) || "infra_error".equals(status)) return AgentStep.ERROR;
        return AgentStep.FAILED;
    }

    private Map<String, Object> buildAiReports(SandboxResultMessage msg) {
        Map<String, Object> reports = new LinkedHashMap<>();
        if (msg.preflightReport() != null) {
            reports.put("preflight_report", msg.preflightReport());
        }
        if (msg.executionResult() != null) {
            reports.put("execution_result", msg.executionResult());
        }

        putIfNotEmpty(reports, "judge_report",    msg.judgeReport());
        putIfNotEmpty(reports, "critic_feedback", msg.criticFeedback());
        putIfNotEmpty(reports, "refiner_report",  msg.refinerReport());
        putIfNotEmpty(reports, "validation_plan", msg.validationPlan());
        putIfNotEmpty(reports, "metrics",         msg.metrics());
        List<String> events = msg.events();
        if (events != null && !events.isEmpty())             reports.put("events", events);
        return reports;
    }

    private void putIfNotEmpty(Map<String, Object> target, String key, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) target.put(key, value);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
