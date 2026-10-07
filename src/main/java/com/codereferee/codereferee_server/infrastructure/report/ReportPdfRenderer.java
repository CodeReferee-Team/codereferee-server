package com.codereferee.codereferee_server.infrastructure.report;

import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * 검증 결과(TaskStatus.aiReports)를 상세 PDF로 만든다.
 *
 * 헤드리스 브라우저 대신 openhtmltopdf로 서버에서 직접 XHTML을 렌더한다. 컨테이너가 필요 없고
 * 리포트 데이터가 이미 서버에 있다. 화면 ReportView와 같은 aiReports 계약을 읽으므로 내용이
 * 갈라지지 않는다. 각 섹션은 데이터가 있을 때만 렌더한다(빌드 실패·SLO 위반·카오스 등 케이스별로
 * 들어오는 리포트가 다르다).
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
            // 한글 임베드. openhtmltopdf는 시스템 CJK 폰트로 폴백하지 않고 OTF도 안정적으로 임베드하지
            // 못한다. Pretendard TTF(OFL)를 번들해 등록한다. 쓰인 글리프만 subset 임베드된다.
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
        Map<String, Object> preflight = asMap(reports.get("preflight_report"));
        Map<String, Object> execution = asMap(reports.get("execution_result"));
        Map<String, Object> sandbox = asMap(execution.get("sandbox_report"));
        Map<String, Object> judge = asMap(reports.get("judge_report"));
        Map<String, Object> critic = asMap(reports.get("critic_feedback"));
        Map<String, Object> refiner = asMap(reports.get("refiner_report"));
        Map<String, Object> plan = asMap(reports.get("validation_plan"));
        Map<String, Object> metrics = asMap(reports.get("metrics"));
        List<Object> events = asList(reports.get("events"));

        String verdict = task.currentAgent() != null ? task.currentAgent().name() : "UNKNOWN";
        String commit = firstNonEmpty(str(preflight.get("resolved_commit_sha")), str(task.commitSha()));
        String stack = firstNonEmpty(str(sandbox.get("detected_stack")), str(preflight.get("detected_stack")));

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"utf-8\"/>").append(css()).append("</head><body>");

        // 헤더
        sb.append("<h1>CodeReferee 검증 리포트</h1>");
        sb.append("<p class=\"sub\">").append(esc(task.repositoryUrl())).append("</p>");
        sb.append("<span class=\"verdict ").append(esc(verdict)).append("\">").append(esc(verdict)).append("</span>");

        // 개요
        sb.append("<table>");
        row(sb, "레포지토리", task.repositoryUrl());
        row(sb, "브랜치", task.branch());
        row(sb, "커밋", commit);
        row(sb, "스택", stack);
        row(sb, "판정 사유", str(judge.get("reason_category")));
        row(sb, "반복 횟수", String.valueOf(task.iterationCount()));
        sb.append("</table>");

        // 판정
        if (!judge.isEmpty()) {
            section(sb, "판정 (Judge)");
            sb.append("<table>");
            row(sb, "상태", str(judge.get("status")));
            row(sb, "사유 코드", str(judge.get("reason_category")));
            row(sb, "설명", str(judge.get("reason")));
            sb.append("</table>");
            bullets(sb, "근거", asList(judge.get("evidence")));
        }

        // 샌드박스 실행
        if (!sandbox.isEmpty() || !execution.isEmpty()) {
            section(sb, "샌드박스 실행");
            sb.append("<table>");
            row(sb, "스택", str(sandbox.get("detected_stack")));
            row(sb, "결과", str(sandbox.get("outcome")));
            row(sb, "실패 단계", str(sandbox.get("failed_step")));
            row(sb, "종료 코드", str(firstNonNull(sandbox.get("exit_code"), execution.get("exit_code"))));
            row(sb, "타임아웃", str(execution.get("timed_out")));
            row(sb, "소요(ms)", str(execution.get("duration_ms")));
            if (execution.get("infra_error") != null) row(sb, "인프라 오류", str(execution.get("infra_error")));
            sb.append("</table>");
            stepsTable(sb, asList(sandbox.get("steps")));
        }

        // 지표 (런타임/SLO 케이스에만 의미 있는 값이 들어온다)
        String metricsHtml = metricsRows(metrics);
        if (!metricsHtml.isEmpty()) {
            section(sb, "지표");
            sb.append("<table>").append(metricsHtml).append("</table>");
        }
        Map<String, Object> chaos = asMap(firstNonNull(metrics.get("chaos_observation"), execution.get("chaos_observation")));
        if (!chaos.isEmpty()) {
            section(sb, "카오스 관측");
            sb.append("<table>");
            row(sb, "복구됨", str(chaos.get("recovered")));
            row(sb, "복구 시간(s)", str(chaos.get("recovery_seconds")));
            row(sb, "유형", str(chaos.get("type")));
            sb.append("</table>");
        }

        // 원인 분석
        if (!critic.isEmpty()) {
            section(sb, "원인 분석 (Critic)");
            sb.append("<table>");
            row(sb, "문제", str(critic.get("issue")));
            row(sb, "근본 원인", str(critic.get("root_cause")));
            row(sb, "권장 조치", str(critic.get("recommended_action")));
            sb.append("</table>");
            bullets(sb, "근거", asList(critic.get("evidence")));
        }

        // 개선 제안
        if (!refiner.isEmpty()) {
            section(sb, "개선 제안 (Refiner)");
            sb.append("<table>");
            row(sb, "요약", str(refiner.get("summary")));
            row(sb, "위험도", str(refiner.get("risk")));
            sb.append("</table>");
            bullets(sb, "수정 가이드", asList(refiner.get("patch_guidance")));
            bullets(sb, "재검증 절차", asList(refiner.get("verification_steps")));
        }

        // 검증 계획
        if (!plan.isEmpty()) {
            section(sb, "검증 계획");
            sb.append("<table>");
            row(sb, "목표", str(plan.get("objective")));
            sb.append("</table>");
            bullets(sb, "검증 범위", asList(plan.get("validation_scope")));
            bullets(sb, "필요 지표", asList(plan.get("metrics_required")));
            bullets(sb, "중단 조건", asList(plan.get("stop_conditions")));
            bullets(sb, "카오스 시나리오", asList(plan.get("chaos_scenarios")));
        }

        // 타임라인
        if (!events.isEmpty()) {
            section(sb, "타임라인");
            sb.append("<ol class=\"timeline\">");
            for (Object ev : events) {
                sb.append("<li>").append(esc(eventLine(ev))).append("</li>");
            }
            sb.append("</ol>");
        }

        sb.append("<p class=\"foot\">task ").append(esc(task.taskId())).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** 런타임/SLO 지표 중 값이 있는 것만 한국어 라벨로. 빌드 실패 케이스면 빈 문자열. */
    private static String metricsRows(Map<String, Object> m) {
        if (m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        String[][] keys = {
            {"p95_latency_ms", "p95 지연(ms)"}, {"p99_latency_ms", "p99 지연(ms)"},
            {"error_rate", "오류율"}, {"availability", "가용성"},
            {"cpu_usage_percent", "CPU(%)"}, {"memory_usage_mb", "메모리(MB)"},
            {"memory_limit_mb", "메모리 한도(MB)"}, {"restart_count", "재시작"},
            {"db_connection_errors", "DB 연결오류"}, {"redis_connection_errors", "Redis 연결오류"},
            {"request_count", "요청 수"},
        };
        for (String[] k : keys) {
            Object v = m.get(k[0]);
            if (v != null) rowRaw(sb, k[1], str(v));
        }
        Map<String, Object> patch = asMap(m.get("patch_check"));
        if (!patch.isEmpty()) {
            rowRaw(sb, "패치 검사", str(patch.get("accepted")) + " (" + str(patch.get("reason_code")) + ")");
        }
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("<h2>").append(esc(title)).append("</h2>");
    }

    private static void bullets(StringBuilder sb, String label, List<Object> items) {
        if (items.isEmpty()) return;
        sb.append("<p class=\"lbl\">").append(esc(label)).append("</p><ul>");
        for (Object it : items) sb.append("<li>").append(esc(flatten(it))).append("</li>");
        sb.append("</ul>");
    }

    private static void stepsTable(StringBuilder sb, List<Object> steps) {
        if (steps.isEmpty()) return;
        sb.append("<p class=\"lbl\">단계별 실행</p>");
        sb.append("<table><tr><th>단계</th><th>종료 코드</th><th>소요(ms)</th></tr>");
        for (Object o : steps) {
            Map<String, Object> s = asMap(o);
            sb.append("<tr><td>").append(esc(str(s.get("name"))))
              .append("</td><td>").append(esc(str(s.get("exit_code"))))
              .append("</td><td>").append(esc(str(s.get("duration_ms")))).append("</td></tr>");
        }
        sb.append("</table>");
    }

    private static String eventLine(Object ev) {
        if (ev instanceof Map) {
            Map<String, Object> m = asMap(ev);
            String step = firstNonEmpty(str(m.get("step")), str(m.get("type")), str(m.get("event")));
            String round = str(m.get("round"));
            return step + (round.isEmpty() ? "" : " (round " + round + ")");
        }
        return flatten(ev);
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return;
        rowRaw(sb, label, value);
    }

    private static void rowRaw(StringBuilder sb, String label, String value) {
        sb.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(value)).append("</td></tr>");
    }

    private static String css() {
        return "<style>"
            + "body{font-family:'Pretendard',sans-serif;color:#1a1a1a;font-size:11px;line-height:1.55;margin:40px;}"
            + "h1{font-size:20px;margin:0 0 4px;} h2{font-size:13px;margin:22px 0 6px;border-bottom:1px solid #e5e5e5;padding-bottom:3px;}"
            + ".sub{color:#666;margin:0 0 12px;} .foot{color:#999;margin-top:24px;font-size:10px;}"
            + ".lbl{font-weight:bold;margin:10px 0 2px;color:#444;}"
            + ".verdict{display:inline-block;padding:4px 12px;border-radius:6px;font-weight:bold;}"
            + ".PASSED{background:#e6f4ea;color:#137333;} .FAILED{background:#fce8e6;color:#c5221f;} .ERROR{background:#fef7e0;color:#b06000;}"
            + "table{border-collapse:collapse;width:100%;margin-top:6px;}"
            + "th,td{border:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top;}"
            + "th{background:#f6f6f6;width:150px;font-weight:600;}"
            + "ul,ol{margin:4px 0 4px 18px;padding:0;} li{margin:1px 0;}"
            + ".timeline li{color:#555;}"
            + "</style>";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 리스트 항목이 맵이면 값들을 공백으로 이어 한 줄로. 문자열이면 그대로. */
    private static String flatten(Object o) {
        if (o instanceof Map) {
            StringBuilder s = new StringBuilder();
            for (Object v : ((Map<?, ?>) o).values()) {
                if (v == null) continue;
                if (s.length() > 0) s.append(" · ");
                s.append(v);
            }
            return s.toString();
        }
        return str(o);
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        return "";
    }

    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }

    /** PDF HTML 주입 방지. 동적 값은 반드시 이걸 거친다. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static class ReportRenderException extends RuntimeException {
        public ReportRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
