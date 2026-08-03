package com.codereferee.codereferee_server.infrastructure.mock;

import com.codereferee.codereferee_server.infrastructure.redis.InputMessage;
import com.codereferee.codereferee_server.infrastructure.redis.RedisValidationRequestQueue;
import com.codereferee.codereferee_server.infrastructure.redis.ResultQueueConsumer;
import com.codereferee.codereferee_server.infrastructure.redis.SandboxResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 모듈이 완성되기 전까지 큐 계약을 대신 이행하는 가짜 워커
 * input 큐를 BLPOP으로 소비, 결과를 output 큐에 RPUSH
 *
 * fail이면 status = fail (코드 결함)
 * infra면 status = infra_error (인프라 오류)
 * 그 외면 status = success
 */

@Slf4j
@Component
@Profile("mock-ai")
@RequiredArgsConstructor
public class MockAiWorker {

    // 일 처리 시간 의도적으로 설정
    private static final Duration POP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROCESSING_DELAY = Duration.ofSeconds(2);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "mock-ai-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        executor.submit(this::consumeLoop);
        log.info("[MockAI] MockAI Worker가 일을 시작했습니다. key = {}", RedisValidationRequestQueue.QUEUE_KEY);
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    private void consumeLoop() {
        while (running) {
            try {
                Object raw = redisTemplate.opsForList()
                        .leftPop(RedisValidationRequestQueue.QUEUE_KEY, POP_TIMEOUT);
                if (raw != null) {
                    process(raw);
                }
            } catch (Exception e) {
                if (!running) return;
                log.error("[MockAI] 실행 실패, 재시도 중", e);
                sleepQuietly(Duration.ofSeconds(1));
            }
        }
    }

    void process(Object raw) {
        InputMessage input = objectMapper.convertValue(raw, InputMessage.class);
        log.info("[MockAI] 작업을 접수했습니다. taskId = {}, repo = {}", input.taskId(), input.repositoryUrl());

        sleepQuietly(PROCESSING_DELAY); // 실제 파이프라인 처리 시간을 흉내낸다.

        SandboxResultMessage result = buildResult(input);
        redisTemplate.opsForList().rightPush(ResultQueueConsumer.QUEUE_KEY, result);
        log.info("[MockAI] taskId = {} -> status = {} 결과를 output에 push했습니다.", input.taskId(), result.status());
    }

    private SandboxResultMessage buildResult(InputMessage input) {
        String url = input.repositoryUrl() == null ? "" : input.repositoryUrl();

        if (url.contains("infra")) {
            return resultOf(input, "infra_error", null, null,
                    List.of("PREFLIGHT", "BASELINE", "INFRA_ERROR: sandbox instance unreachable"));
        }

        if (url.contains("fail")) {
            return resultOf(input, "fail",
                    Map.of("verdict", "fail", "reason", "chaos experiment pod-delete: recovery timeout"),
                    Map.of("cause", "no readiness probe, single replica", "rounds_used", 3),
                    List.of("PREFLIGHT", "BASELINE", "CHAOS", "JUDGING", "REFINING 3/3", "FAILED"));
        }

        return resultOf(input, "success",
                Map.of("verdict", "pass", "score", 92),
                null,
                List.of("PREFLIGHT", "BASELINE", "CHAOS", "JUDGING", "PASSED"));
    }

    private SandboxResultMessage resultOf(InputMessage input,
                                          String status,
                                          Map<String, Object> judgeReport,
                                          Map<String, Object> criticFeedback,
                                          List<String> events) {
        return new SandboxResultMessage(
                input.taskId(),
                null,
                status,
                input.repositoryUrl(),
                input.branch(),
                input.commitSha(),
                Map.of("experiments", List.of("pop-delete", "network-latency")),
                Map.of("url_valid", true, "git_ls_remote", "ok"),
                Map.of("baseline", "pass", "chaos_round", 1),
                judgeReport,
                criticFeedback,
                null,
                Map.of("cpu_p95", 0.42, "restart_count", 0),
                events
        );
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

}
