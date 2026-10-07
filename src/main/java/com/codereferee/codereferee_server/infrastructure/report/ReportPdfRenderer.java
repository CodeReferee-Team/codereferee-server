package com.codereferee.codereferee_server.infrastructure.report;

import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 검증 결과(TaskStatus.aiReports)를 PDF로 만든다.
 *
 * 헤드리스 브라우저 대신 openhtmltopdf로 서버에서 직접 XHTML을 렌더한다. 컨테이너가 필요 없고
 * 리포트 데이터가 이미 서버에 있으므로 가장 단순하다. 화면 ReportView와 같은 aiReports 계약을
 * 읽으므로 내용이 갈라지지 않는다.
 *
 * aiReports·repositoryUrl은 외부(레포·AI)에서 온 값이라 그대로 넣으면 PDF HTML에 주입될 수 있다.
 * 모든 동적 값을 escape한다.
 */
@Component
public class ReportPdfRenderer {

    public byte[] render(TaskStatus task) {
        String html = buildXhtml(task);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // 한글 임베드. openhtmltopdf는 시스템 CJK 폰트로 폴백하지 않아, 폰트가 없으면
            // 한글이 전부 두부(□)로 나온다. Pretendard(OFL)를 번들해 등록한다. 실제 쓰인
            // 글리프만 subset 임베드되므로 PDF는 작게 유지된다.
            builder.useFont(
                    () -> getClass().getResourceAsStream("/fonts/Pretendard-Regular.ttf"),
                    "Pretendard");
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ReportRenderException("리포트 PDF 생성 실패: task=" + task.taskId(), e);
        }
    }

    private String buildXhtml(TaskStatus task) {
        Map<String, Object> reports = task.aiReports() != null ? task.aiReports() : Map.of();
        Map<String, Object> judge = asMap(reports.get("judge_report"));
        Map<String, Object> critic = asMap(reports.get("critic_feedback"));
        Map<String, Object> refiner = asMap(reports.get("refiner_report"));

        String verdict = task.currentAgent() != null ? task.currentAgent().name() : "UNKNOWN";
        String reasonCategory = str(judge.get("reason_category"));
        String reason = str(judge.get("reason"));
        String rootCause = str(critic.get("root_cause"));
        String refinerSummary = str(refiner.get("summary"));

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\"/><style>")
          .append("body{font-family:'Pretendard',sans-serif;color:#1a1a1a;font-size:12px;line-height:1.6;margin:40px;}")
          .append("h1{font-size:20px;margin:0 0 4px;} .sub{color:#666;margin:0 0 20px;}")
          .append(".verdict{display:inline-block;padding:4px 12px;border-radius:6px;font-weight:bold;}")
          .append(".PASSED{background:#e6f4ea;color:#137333;} .FAILED{background:#fce8e6;color:#c5221f;}")
          .append(".ERROR{background:#fef7e0;color:#b06000;}")
          .append("table{border-collapse:collapse;width:100%;margin-top:16px;}")
          .append("th,td{border:1px solid #ddd;padding:8px;text-align:left;vertical-align:top;}")
          .append("th{background:#f5f5f5;width:160px;}")
          .append("</style></head><body>");

        sb.append("<h1>CodeReferee 검증 리포트</h1>");
        sb.append("<p class=\"sub\">").append(esc(task.repositoryUrl())).append("</p>");
        sb.append("<span class=\"verdict ").append(esc(verdict)).append("\">").append(esc(verdict)).append("</span>");

        sb.append("<table>");
        row(sb, "레포지토리", task.repositoryUrl());
        row(sb, "브랜치", task.branch());
        row(sb, "커밋", task.commitSha());
        row(sb, "판정 사유", reasonCategory);
        if (!reason.isEmpty()) row(sb, "상세", reason);
        if (!rootCause.isEmpty()) row(sb, "원인 분석", rootCause);
        if (!refinerSummary.isEmpty()) row(sb, "개선 제안", refinerSummary);
        row(sb, "반복 횟수", String.valueOf(task.iterationCount()));
        sb.append("</table>");

        sb.append("<p class=\"sub\" style=\"margin-top:24px;\">task ")
          .append(esc(task.taskId())).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(value)).append("</td></tr>");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** PDF HTML 주입 방지. 동적 값은 반드시 이걸 거친다. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static class ReportRenderException extends RuntimeException {
        public ReportRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
