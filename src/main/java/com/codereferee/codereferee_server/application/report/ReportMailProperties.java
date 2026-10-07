package com.codereferee.codereferee_server.application.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리포트 메일 발송 설정.
 *
 * enabled가 false면 발송 경로 전체를 건너뛴다. SMTP가 없는 환경(CI·로컬 기본)에서
 * 기능이 조용히 꺼져 있도록 기본값을 false로 둔다.
 */
@ConfigurationProperties(prefix = "app.report-mail")
public record ReportMailProperties(
        boolean enabled,
        String from,
        int maxPerHour
) {
    public ReportMailProperties {
        if (from == null || from.isBlank()) {
            from = "CodeReferee <noreply@codereferee.dev>";
        }
        if (maxPerHour <= 0) {
            maxPerHour = 100;
        }
    }
}
