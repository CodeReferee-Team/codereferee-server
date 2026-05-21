package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DraftTaskQueue {

    public static final String QUEUE_KEY = "agent:draft:queue";

    private final RedisTemplate<String, Object> redisTemplate;

    public void enqueue(DraftTaskMessage message) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
    }
}
