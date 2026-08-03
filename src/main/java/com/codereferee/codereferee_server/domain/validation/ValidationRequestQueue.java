package com.codereferee.codereferee_server.domain.validation;

// 검증 요청을 AI 모듈에 전달하는 Port
public interface ValidationRequestQueue {
    void enqueue(TaskStatus initial);
}
