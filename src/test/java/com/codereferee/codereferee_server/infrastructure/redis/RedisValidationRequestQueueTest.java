package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisValidationRequestQueueTest {

    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 5, 21, 16, 0);
    private static final String REPO = "https://github.com/phdcoco/QuickByte_Demo";

    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final ListOperations<String, Object> listOperations = mock(ListOperations.class);
    private final RedisValidationRequestQueue queue = new RedisValidationRequestQueue(redisTemplate);

    private TaskStatus queued(ChaosOptions chaos) {
        return TaskStatus.queued("task-1", REPO, "main", "d1c5c5e", chaos, SUBMITTED_AT);
    }

    @Test
    void enqueueConvertsStatusToQueueContractMessage() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        queue.enqueue(queued(ChaosOptions.NONE));

        verify(listOperations).rightPush(RedisValidationRequestQueue.QUEUE_KEY,
                new InputMessage("task-1", REPO, "main", "d1c5c5e", null, null, SUBMITTED_AT));
    }

    @Test
    void chaosOptionsRideAlongToTheQueue() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        queue.enqueue(queued(ChaosOptions.of("litmus_pod_delete", "quickbyte-demo")));

        // 옵션이 조용히 누락되면 샌드박스가 일반 검증으로 돌아버린다. 값까지 확인한다.
        verify(listOperations).rightPush(RedisValidationRequestQueue.QUEUE_KEY,
                new InputMessage("task-1", REPO, "main", "d1c5c5e",
                        "litmus_pod_delete", "quickbyte-demo", SUBMITTED_AT));
    }
}
