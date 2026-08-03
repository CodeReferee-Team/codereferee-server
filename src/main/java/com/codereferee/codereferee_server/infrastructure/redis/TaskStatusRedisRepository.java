package com.codereferee.codereferee_server.infrastructure.redis;

import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TaskStatusRedisRepository {

    private static final String KEY_PREFIX = "task:status:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(TaskStatus status) {
        redisTemplate.opsForValue().set(KEY_PREFIX + status.taskId(), status);
    }

    public Optional<TaskStatus> findById(String taskId) {
        Object value = redisTemplate.opsForValue().get(KEY_PREFIX + taskId);
        if (value == null) return Optional.empty();
        return Optional.of(objectMapper.convertValue(value, TaskStatus.class));
    }
}
