package com.codereferee.codereferee_server.referee;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InputQueue {

    public static final String QUEUE_KEY = "codereferee:workflow:input";

    private final RedisTemplate<String, Object> redisTemplate;

    public void enqueue(InputMessage message) {
        redisTemplate.opsForList().rightPush(QUEUE_KEY, message);
    }
}
