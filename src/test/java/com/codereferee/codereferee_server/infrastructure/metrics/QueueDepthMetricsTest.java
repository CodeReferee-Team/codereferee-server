package com.codereferee.codereferee_server.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueDepthMetricsTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, Object> listOperations = mock(ListOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private Gauge gauge(String queue) {
        return registry.find("codereferee.queue.depth").tag("queue", queue).gauge();
    }

    @Test
    void reportsDepthOfBothQueues() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("codereferee:workflow:input")).thenReturn(7L);
        when(listOperations.size("codereferee:workflow:output")).thenReturn(2L);

        new QueueDepthMetrics(redisTemplate, registry);

        assertThat(gauge("input").value()).isEqualTo(7.0);
        assertThat(gauge("output").value()).isEqualTo(2.0);
    }

    /**
     * Redis가 죽었을 때 0을 보고하면 "큐가 비었다"는 거짓이 되고, 예외를 올리면
     * /actuator/prometheus 응답 전체가 깨져 장애 중에 다른 지표도 못 본다.
     */
    @Test
    void reportsNotANumberInsteadOfZeroWhenRedisIsUnreachable() {
        when(redisTemplate.opsForList()).thenThrow(new QueryTimeoutException("redis down"));

        new QueueDepthMetrics(redisTemplate, registry);

        assertThat(gauge("input").value()).isNaN();
        assertThat(gauge("output").value()).isNaN();
    }

    /** 키가 아직 없으면 Redis가 null을 준다. 0이 아니라 값 없음으로 다룬다. */
    @Test
    void treatsMissingKeyAsNoValue() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("codereferee:workflow:input")).thenReturn(null);
        when(listOperations.size("codereferee:workflow:output")).thenReturn(0L);

        new QueueDepthMetrics(redisTemplate, registry);

        assertThat(gauge("input").value()).isNaN();
        assertThat(gauge("output").value()).isEqualTo(0.0);
    }
}
