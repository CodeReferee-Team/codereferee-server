package com.codereferee.codereferee_server.referee;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PipelineMetrics {

    private final MeterRegistry registry;
    private final Counter submissionsTotal;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.submissionsTotal = Counter.builder("codereferee.submissions")
                .description("Total number of code review submissions")
                .register(registry);
    }

    public void recordSubmission() {
        submissionsTotal.increment();
    }

    /** 최종 판정(PASSED/FAILED/ERROR)별 카운터 — ERROR 비율이 높으면 인프라 문제 신호 */
    public void recordVerdict(AgentStep verdict) {
        registry.counter("codereferee.verdicts", "result", verdict.name()).increment();
    }

    /** QUEUED→PASSED, JUDGING→FAILED 등 상태 전이마다 태그별 카운터 증가 */
    public void recordTransition(AgentStep from, AgentStep to) {
        registry.counter("codereferee.agent.transitions",
                "from", from.name(), "to", to.name()).increment();
    }
}
