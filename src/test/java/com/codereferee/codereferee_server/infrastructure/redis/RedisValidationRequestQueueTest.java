package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
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
        RedisValidationRequestQueue queue = new RedisValidationRequestQueue(redisTemplate);

        LocalDateTime submittedAt = LocalDateTime.of(2026, 5, 21, 16, 0);
        TaskStatus initial = new TaskStatus(
                "task-1", AgentStep.QUEUED, false, 0, null, submittedAt,
                "https://github.com/phdcoco/QuickByte_Demo", "main", "d1c5c5e", null
        );

        when(redisTemplate.opsForList()).thenReturn(listOperations);


        queue.enqueue(initial);


        verify(listOperations).rightPush(RedisValidationRequestQueue.QUEUE_KEY, new InputMessage(
                "task-1",
                "https://github.com/phdcoco/QuickByte_Demo",
                "main",
                "d1c5c5e",
                submittedAt
        ));
    }
}
