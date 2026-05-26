package com.codereferee.codereferee_server.referee;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftTaskQueueTest {

    @SuppressWarnings("unchecked")
    @Test
    void enqueuePushesDraftTaskToRedisQueue() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ListOperations<String, Object> listOperations = mock(ListOperations.class);
        DraftTaskQueue draftTaskQueue = new DraftTaskQueue(redisTemplate);
        DraftTaskMessage message = new DraftTaskMessage(
                "task-1",
                "https://github.com/test/repo",
                "main",
                "sha456abc",
                LocalDateTime.of(2026, 5, 21, 16, 0)
        );

        when(redisTemplate.opsForList()).thenReturn(listOperations);

        draftTaskQueue.enqueue(message);

        verify(listOperations).rightPush(DraftTaskQueue.QUEUE_KEY, message);
    }
}
