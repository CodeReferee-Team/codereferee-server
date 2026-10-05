package com.codereferee.codereferee_server.infrastructure.metrics;

import com.codereferee.codereferee_server.infrastructure.redis.RedisValidationRequestQueue;
import com.codereferee.codereferee_server.infrastructure.redis.ResultQueueConsumer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 두 큐에 쌓인 메시지 수.
 *
 * input이 계속 쌓이면 AI 워커가 못 따라오거나 죽은 것이고, output이 쌓이면 BE의
 * 소비 루프가 멈춘 것이다. 카운터만으로는 둘을 구분할 수 없어 적체를 직접 본다.
 */
@Slf4j
@Component
public class QueueDepthMetrics {

    private final RedisTemplate<String, Object> redisTemplate;

    public QueueDepthMetrics(RedisTemplate<String, Object> redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;

        register(registry, RedisValidationRequestQueue.QUEUE_KEY, "input",
                "Pending validation requests waiting for the AI worker");
        register(registry, ResultQueueConsumer.QUEUE_KEY, "output",
                "Results and progress events waiting to be consumed by this server");
    }

    private void register(MeterRegistry registry, String key, String direction, String description) {
        Gauge.builder("codereferee.queue.depth", this, self -> self.depth(key))
                .description(description)
                .tag("queue", direction)
                .register(registry);
    }

    /**
     * Gauge는 scrape 시점에 호출된다. Redis가 죽었을 때 예외가 올라가면 /actuator/prometheus
     * 응답 전체가 깨져서, 하필 장애 상황에 다른 지표까지 같이 못 보게 된다.
     * 그래서 실패는 NaN으로 돌려준다. Prometheus는 NaN을 "값 없음"으로 다루므로
     * 0으로 보고해 "큐가 비었다"고 거짓말하는 것보다 정확하다.
     */
    private double depth(String key) {
        try {
            Long size = redisTemplate.opsForList().size(key);
            return size != null ? size : Double.NaN;
        } catch (RuntimeException e) {
            log.warn("[QueueDepth] {} 길이를 읽지 못했습니다: {}", key, e.getMessage());
            return Double.NaN;
        }
    }
}
