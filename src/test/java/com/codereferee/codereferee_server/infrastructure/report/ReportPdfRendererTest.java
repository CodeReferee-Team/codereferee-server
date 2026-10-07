package com.codereferee.codereferee_server.infrastructure.report;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfRendererTest {

    private final ReportPdfRenderer renderer = new ReportPdfRenderer();

    private TaskStatus terminal(Map<String, Object> aiReports) {
        return TaskStatus.queued("t1", "https://github.com/o/r", "main", "abc123",
                        "dev@example.com", ChaosOptions.NONE, null)
                .withAiResult(AgentStep.FAILED, false, "fail", aiReports);
    }

    @Test
    void rendersANonEmptyPdf() {
        byte[] pdf = renderer.render(terminal(Map.of(
                "judge_report", Map.of("reason_category", "test_failure", "reason", "2 tests failed"))));

        assertThat(pdf).isNotEmpty();
        // PDF 매직 바이트
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void rendersEvenWhenReportsAreEmpty() {
        // 내용이 비어도(판정만 있고 리포트가 없을 때) 깨지지 않아야 한다.
        byte[] pdf = renderer.render(terminal(Map.of()));
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void koreanTextRendersWithEmbeddedFont() {
        // 한글 폰트가 임베드되지 않으면 두부(□)로 나온다. 한글 사유를 넣어 렌더가
        // 깨지지 않고 PDF가 나오는지(폰트 리소스 로드 포함) 확인한다.
        byte[] pdf = renderer.render(terminal(Map.of(
                "judge_report", Map.of("reason_category", "latency_slo_violation",
                        "reason", "p95 지연이 임계값을 초과했습니다"),
                "critic_feedback", Map.of("root_cause", "동기 외부 호출이 요청 스레드를 붙잡습니다"))));
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        // 한글 TTF가 subset 임베드되면 글리프 데이터로 커진다. 두부 폴백(임베드 실패)이면 작다.
        assertThat(pdf.length).isGreaterThan(8000);
        try { java.nio.file.Files.write(java.nio.file.Path.of("build","korean-check.pdf"), pdf); } catch (Exception ignored) {}
    }

    @Test
    void hostileReportTextDoesNotBreakRendering() {
        // aiReports·repo는 외부 입력이다. HTML 특수문자가 렌더를 깨거나 주입되면 안 된다.
        byte[] pdf = renderer.render(terminal(Map.of(
                "judge_report", Map.of("reason_category", "x", "reason", "<script>&\"'</script> <td>"))));
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
