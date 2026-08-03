package com.codereferee.codereferee_server.domain.validation;

/**
 * 검증 요청의 상태 머신.
 * QUEUED → PREFLIGHT → BASELINE → CHAOS → JUDGING → (REFINING → BASELINE ...) → PASSED | FAILED | ERROR
 * PASSED / FAILED : 코드에 대한 판정 결과 (FAIL은 Critic/Refiner 루프를 거친 최종 실패)
 * ERROR           : 인프라/파이프라인 오류로 판정 불가. 코드 결함이 아니므로 Critic 분석 대상이 아니다.
 */
public enum AgentStep {
    QUEUED,
    PREFLIGHT,
    BASELINE,
    CHAOS,
    JUDGING,
    REFINING,
    PASSED,
    FAILED,
    ERROR;

    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == ERROR;
    }
}
