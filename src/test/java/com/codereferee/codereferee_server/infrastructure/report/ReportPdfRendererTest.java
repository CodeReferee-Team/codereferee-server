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
    void hostileReportTextDoesNotBreakRendering() {
        // aiReports·repo는 외부 입력이다. HTML 특수문자가 렌더를 깨거나 주입되면 안 된다.
        byte[] pdf = renderer.render(terminal(Map.of(
                "judge_report", Map.of("reason_category", "x", "reason", "<script>&\"'</script> <td>"))));
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }
}
