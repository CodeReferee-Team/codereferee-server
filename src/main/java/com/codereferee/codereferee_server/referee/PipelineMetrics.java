package com.codereferee.codereferee_server.referee;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class PipelineMetrics {

    private final MeterRegistry registry;
    private final Counter submissionsTotal;
    private final Counter sandboxFailuresTotal;
    private final AtomicLong cumulativeIterations = new AtomicLong(0);

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.submissionsTotal = Counter.builder("codereferee.submissions")
                .description("Total number of code review submissions")
                .register(registry);

        this.sandboxFailuresTotal = Counter.builder("codereferee.sandbox.failures")
                .description("Total sandbox compilation or execution failures")
                .register(registry);

        Gauge.builder("codereferee.active.iterations", cumulativeIterations, AtomicLong::get)
                .description("Cumulative agent ping-pong iteration count across active tasks")
                .register(registry);
    }

    public void recordSubmission() {
        submissionsTotal.increment();
    }

    public void recordSandboxFailure() {
        sandboxFailuresTotal.increment();
    }

    /** SANDBOX→CRITIC, CRITIC→REFINER 등 에이전트 전이마다 태그별 카운터 증가 */
    public void recordTransition(AgentStep from, AgentStep to) {
        registry.counter("codereferee.agent.transitions",
                "from", from.name(), "to", to.name()).increment();
    }

    public void addIterations(int delta) {
        if (delta > 0) cumulativeIterations.addAndGet(delta);
    }
}
