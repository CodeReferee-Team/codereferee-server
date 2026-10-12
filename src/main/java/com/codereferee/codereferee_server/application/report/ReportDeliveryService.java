package com.codereferee.codereferee_server.application.report;

import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.infrastructure.report.ReportMailSender;
import com.codereferee.codereferee_server.infrastructure.report.ReportPdfRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 종단 상태에 도달한 검증의 PDF 리포트를 제출자 메일로 보낸다.
 *
 * 발송은 best-effort다. 어떤 실패도 검증 파이프라인을 깨뜨리지 않는다(결과 저장이 우선).
 *
 * 로그인이 없어 임의 주소로 보낼 수 있으므로(오픈 릴레이), 두 겹으로 막는다:
 *  - task당 1회: 같은 task의 중복/재시도 결과가 메일을 두 번 보내지 않게 한다(SETNX).
 *  - 전역 레이트리밋: 시간당 발송 수를 상한으로 눌러 스팸 폭주의 피해 범위를 제한한다(INCR+만료).
 * 수신 주소가 제출자 본인인지는 검증하지 않는다(확인 링크는 후속 과제).
 *
 * 카운터·플래그는 INCR/SETNX 원자성과 직렬화 문제 회피를 위해 StringRedisTemplate을 쓴다.
 */
@Slf4j
@Service
public class ReportDeliveryService {

    private static final String ONCE_KEY = "report-mail:sent:";
    private static final String RATE_KEY = "report-mail:count:";
    private static final Duration ONCE_TTL = Duration.ofHours(1);
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final StringRedisTemplate redis;
    private final ReportPdfRenderer renderer;
    private final ReportMailSender mailSender;
    private final ReportMailProperties properties;

    public ReportDeliveryService(StringRedisTemplate redis, ReportPdfRenderer renderer,
                                 ReportMailSender mailSender, ReportMailProperties properties) {
        this.redis = redis;
        this.renderer = renderer;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** 종단 상태 저장 직후 호출한다. 어떤 경우에도 예외를 던지지 않는다. */
    public void deliver(TaskStatus task) {
        try {
            // 주소가 없으면 할 말이 없으므로 조용히 끝낸다. 대다수 요청이 여기다.
            String email = task.email();
            if (email == null || email.isBlank()) return;

            // 주소를 받았는데 꺼져 있는 것은 다르다. 사용자는 화면에서 "검증이 끝나면
            // PDF 리포트를 보내요"를 보고 주소를 넣었고, 우리는 그 약속을 지키지 않는다.
            //
            // 이 줄이 원래 아무 로그 없이 리턴해서, 운영에서 메일이 안 오는데 서버 로그에
            // 흔적조차 없는 상태였다. 아래 모든 경로에는 로그가 있는데 여기만 없었다.
            // 설정이 빠진 것과 발송이 실패한 것은 고치는 방법이 다르므로 구분해서 남긴다.
            if (!properties.enabled()) {
                log.warn("[ReportMail] task={} 주소를 받았지만 발송이 꺼져 있습니다 (app.report-mail.enabled=false)",
                        task.taskId());
                return;
            }

            if (!claimOnce(task.taskId())) {
                log.debug("[ReportMail] task={} 이미 발송(또는 진행) — 건너뜀", task.taskId());
                return;
            }
            if (!withinRateLimit()) {
                log.warn("[ReportMail] 시간당 상한({}) 초과 — task={} 발송 건너뜀", properties.maxPerHour(), task.taskId());
                return;
            }

            String verdict = task.currentAgent() != null ? task.currentAgent().name() : "UNKNOWN";
            byte[] pdf = renderer.render(task);
            mailSender.send(email, task.repositoryUrl(), verdict, pdf);
            log.info("[ReportMail] task={} -> {} 발송 완료 ({} bytes)", task.taskId(), mask(email), pdf.length);
        } catch (Exception e) {
            // 발송 실패가 판정 결과 저장을 되돌리면 안 된다. 로그만 남긴다.
            log.error("[ReportMail] task={} 발송 실패: {}", task.taskId(), e.toString());
        }
    }

    /** 같은 task가 두 번 보내지 않도록 선점한다. 처음이면 true. */
    private boolean claimOnce(String taskId) {
        Boolean first = redis.opsForValue().setIfAbsent(ONCE_KEY + taskId, "1", ONCE_TTL);
        return Boolean.TRUE.equals(first);
    }

    /** 현재 시간 윈도우의 발송 수를 늘리고 상한 이내인지 본다. */
    private boolean withinRateLimit() {
        String key = RATE_KEY + LocalDateTime.now().format(HOUR);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, RATE_WINDOW);
        }
        return count != null && count <= properties.maxPerHour();
    }

    /** 로그에 주소 전체를 남기지 않는다. */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
