package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.domain.validation.ValidationRequestQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisValidationRequestQueue implements ValidationRequestQueue {

    public static final String QUEUE_KEY = "codereferee:workflow:input";

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void enqueue(TaskStatus initial) {
        // 도메인 상태 → 큐 계약 메시지 변환은 어댑터의 책임
        ChaosOptions chaos = initial.chaosOptions();
        InputMessage message = new InputMessage(
                initial.taskId(), initial.repositoryUrl(), initial.branch(), initial.commitSha(),
                chaos.mode(), chaos.deploymentProfile(), initial.updatedAt()
        );

        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
    }

}
