package com.codereferee.codereferee_server.application.report;

import com.codereferee.codereferee_server.domain.validation.AgentStep;
import com.codereferee.codereferee_server.domain.validation.ChaosOptions;
import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.codereferee.codereferee_server.infrastructure.report.ReportMailSender;
import com.codereferee.codereferee_server.infrastructure.report.ReportPdfRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

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

    @Test
    void sendFailureDoesNotPropagate() throws Exception {
        // 발송 실패가 파이프라인을 깨면 안 된다. deliver는 예외를 던지지 않는다.
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(), any(), any(), any());
        service(true, 100).deliver(task("dev@example.com")); // 예외 없이 반환해야 통과
        verify(mailSender).send(any(), any(), any(), any());
    }
}
