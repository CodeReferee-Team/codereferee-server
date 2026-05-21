package com.codereferee.codereferee_server.referee;

import org.springframework.stereotype.Component;

@Component
public class PipelineRouter {

    private static final int MAX_ITERATIONS = 5;

    /**
     * SANDBOX 이후 라우팅: 컴파일/실행 불가 시 JUDGE를 건너뛰고 CRITIC으로 숏컷.
     */
    public AgentStep routeAfterSandbox(TaskStatus state) {
        return state.executable() ? AgentStep.JUDGE : AgentStep.CRITIC;
    }

    /**
     * CRITIC 이후 라우팅: 반복 한도 초과 시 FAILED, 그 외엔 REFINER로 진행.
     */
    public AgentStep routeAfterCritic(TaskStatus state) {
        return state.iterationCount() >= MAX_ITERATIONS ? AgentStep.FAILED : AgentStep.REFINER;
    }
}
