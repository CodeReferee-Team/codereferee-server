package com.codereferee.codereferee_server.application.report;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.infrastructure.report.ReportMailSender;
import com.codereferee.codereferee_server.infrastructure.report.ReportPdfRenderer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReportDeliveryServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ReportPdfRenderer renderer = mock(ReportPdfRenderer.class);
    private final ReportMailSender mailSender = mock(ReportMailSender.class);

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(renderer.render(any())).thenReturn("%PDF-1.4".getBytes());
        // 기본: 처음 선점 성공 + 레이트리밋 이내
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
    }

    private ReportDeliveryService service(boolean enabled, int maxPerHour) {
        return new ReportDeliveryService(redis, renderer, mailSender,
                new ReportMailProperties(enabled, "from@x.com", maxPerHour));
    }

    private TaskStatus task(String email) {
        return TaskStatus.queued("t1", "https://github.com/o/r", "main", "abc", email, ChaosOptions.NONE, null)
                .withAiResult(AgentStep.FAILED, false, "fail", java.util.Map.of());
    }

    @Test
    void sendsWhenEnabledAndEmailPresent() throws Exception {
        service(true, 100).deliver(task("dev@example.com"));
        verify(mailSender).send(eq("dev@example.com"), anyString(), eq("FAILED"), any());
    }

    @Test
    void doesNotSendWhenDisabled() throws Exception {
        service(false, 100).deliver(task("dev@example.com"));
        verify(mailSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void doesNotSendWithoutEmail() throws Exception {
        service(true, 100).deliver(task(null));
        verify(mailSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void doesNotSendTwiceForTheSameTask() throws Exception {
        // 같은 task 선점이 이미 되어 있으면(중복/재시도 결과) 보내지 않는다.
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        service(true, 100).deliver(task("dev@example.com"));
        verify(mailSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void doesNotSendWhenRateLimitExceeded() throws Exception {
        when(valueOps.increment(anyString())).thenReturn(101L); // 상한 100 초과
        service(true, 100).deliver(task("dev@example.com"));
        verify(mailSender, never()).send(any(), any(), any(), any());
    }

    /**
     * 설정이 빠져 발송이 꺼져 있던 동안, 사용자는 주소를 넣었는데 메일이 오지 않고
     * 서버 로그에도 흔적이 없었다. 조용한 리턴이라 원인을 찾는 데 오래 걸렸다.
     * 이 변경의 동작은 로그 그 자체이므로 로그를 확인한다.
     */
    @Test
    void warnsWhenEmailGivenButSendingIsDisabled() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReportDeliveryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(false, 100).deliver(task("dev@example.com"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("t1"));
    }

    /** 주소를 안 넣은 요청이 대다수다. 거기까지 경고를 남기면 로그가 쓸모없어진다. */
    @Test
    void staysQuietWhenNoEmailWasGiven() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReportDeliveryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service(false, 100).deliver(task(null));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).isEmpty();
    }

    @Test
    void sendFailureDoesNotPropagate() throws Exception {
        // 발송 실패가 파이프라인을 깨면 안 된다. deliver는 예외를 던지지 않는다.
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(), any(), any(), any());
        service(true, 100).deliver(task("dev@example.com")); // 예외 없이 반환해야 통과
        verify(mailSender).send(any(), any(), any(), any());
    }
}
