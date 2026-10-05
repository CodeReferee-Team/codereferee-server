package com.codereferee.codereferee_server.infrastructure.metrics;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class PipelineMetrics {

    /**
     * 요청 전체 소요시간의 기대 구간. 실연동 실측이 62초였고 AI 측 상한이 15~20분이므로
     * 1초~30분이면 양끝이 덮인다. 백분위 히스토그램은 태그 조합마다 버킷을 만들어
     * 시계열이 불어나므로, 범위를 좁혀 버킷 수를 줄인다.
     */
    private static final Duration DURATION_MIN = Duration.ofSeconds(1);
    private static final Duration DURATION_MAX = Duration.ofMinutes(30);

    private final MeterRegistry registry;
    private final Counter submissionsTotal;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.submissionsTotal = Counter.builder("codereferee.submissions")
                .description("Total number of code review submissions")
                .register(registry);

        // Micrometer는 첫 호출 때 카운터를 만든다. 그래서 아직 한 건도 없으면 시계열 자체가
        // 없고, 대시보드에는 0이 아니라 "No data"가 뜬다. 운영 화면에서 "지표가 없다"와
        // "아무 일도 없었다"는 전혀 다른 뜻인데 눈으로는 구분되지 않으므로, 값이 0일 수 있는
        // 조합을 미리 등록해 둔다. 전이(from×to 81가지)는 대부분 불가능한 조합이라 제외한다.
        for (AgentStep step : AgentStep.values()) {
            if (step.isTerminal()) {
                verdictCounter(step);
            } else {
                // 스위퍼는 비종결 상태만 정리하므로 종결 상태 태그는 생기지 않는다.
                sweptCounter(step);
            }
        }
    }

    private Counter verdictCounter(AgentStep verdict) {
        return Counter.builder("codereferee.verdicts")
                .description("Terminal verdicts by result")
                .tag("result", verdict.name())
                .register(registry);
    }

    private Counter sweptCounter(AgentStep from) {
        return Counter.builder("codereferee.sweeper.swept")
                .description("Requests the sweeper gave up on, by the stage they were left in")
                .tag("from", from.name())
                .register(registry);
    }

    public void recordSubmission() {
        submissionsTotal.increment();
    }

    /** 최종 판정(PASSED/FAILED/ERROR)별 카운터 — ERROR 비율이 높으면 인프라 문제 신호 */
    public void recordVerdict(AgentStep verdict) {
        verdictCounter(verdict).increment();
    }

    /** QUEUED→PASSED, JUDGING→FAILED 등 상태 전이마다 태그별 카운터 증가 */
    public void recordTransition(AgentStep from, AgentStep to) {
        registry.counter("codereferee.agent.transitions",
                "from", from.name(), "to", to.name()).increment();
    }

    /**
     * 접수부터 종결까지 걸린 시간. 대시보드의 p95가 여기서 나온다.
     * 판정 결과별로 나누는 이유는 ERROR가 보통 타임아웃이라 성공 경로보다 훨씬 길고,
     * 섞어서 보면 성공 경로의 지연이 ERROR에 가려지기 때문이다.
     */
    public void recordValidationDuration(AgentStep verdict, Duration elapsed) {
        Timer.builder("codereferee.validation.duration")
                .description("Elapsed time from submission to a terminal verdict")
                .tag("result", verdict.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(DURATION_MIN)
                .maximumExpectedValue(DURATION_MAX)
                .register(registry)
                .record(elapsed);
    }

    /**
     * 한 단계에 머문 시간. 어느 단계가 전체 지연을 만드는지 보려면 단계별로 떼어 봐야 한다.
     * 단계 수가 9개라 백분위 히스토그램을 켜면 시계열이 과하게 늘어나므로 합·건수·최댓값만 남긴다.
     */
    public void recordStageDuration(AgentStep stage, Duration dwell) {
        Timer.builder("codereferee.stage.duration")
                .description("Time spent in a single pipeline stage before transitioning out")
                .tag("stage", stage.name())
                .register(registry)
                .record(dwell);
    }

    /**
     * 결과를 받지 못해 스위퍼가 ERROR로 확정한 건수. 떠난 단계를 태그로 남기면
     * 어디서 응답이 끊기는지 보인다(QUEUED는 적체, 그 외는 실행 중 유실).
     */
    public void recordSweptToError(AgentStep from) {
        sweptCounter(from).increment();
    }
}
