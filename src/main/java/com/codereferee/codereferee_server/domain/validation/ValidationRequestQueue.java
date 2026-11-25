package com.codereferee.codereferee_server.domain.validation;

// 검증 요청을 AI 모듈에 전달하는 Port.
// 카오스 옵션은 TaskStatus가 들고 있다. 업로드 설정은 작업 상태와 별개인 요청 입력이다.
// 오버로드와 default 구현을 두면 구현체가 인자를 조용히 버려도 컴파일러가 잡지 못한다.
public interface ValidationRequestQueue {
    void enqueue(TaskStatus initial, String validationYaml);
}
