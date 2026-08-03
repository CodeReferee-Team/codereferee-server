package com.codereferee.codereferee_server.infrastructure.redis;

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

    public void enqueue(TaskStatus initial) {
        InputMessage message = new InputMessage(
                initial.taskId(), initial.repositoryUrl(), initial.branch(),
                initial.commitSha(), initial.updatedAt()
        );

        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
    }
}
