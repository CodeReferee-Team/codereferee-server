package com.codereferee.codereferee_server.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisValidationRequestQueueTest {

    @Test
    void enqueuePushesDraftTaskToRedisQueue() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ListOperations<String, Object> listOperations = mock(ListOperations.class);
        RedisValidationRequestQueue redisValidationRequestQueue = new RedisValidationRequestQueue(redisTemplate);
        InputMessage message = new InputMessage(
                "task-1",
                "https://github.com/phdcoco/QuickByte_Demo",
                "main",
                "d1c5c5e",
                LocalDateTime.of(2026, 5, 21, 16, 0)
        );

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        redisValidationRequestQueue.enqueue(message);

        verify(listOperations).rightPush(RedisValidationRequestQueue.QUEUE_KEY, message);
    }
}
