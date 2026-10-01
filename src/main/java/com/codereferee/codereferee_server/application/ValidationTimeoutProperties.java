package com.codereferee.codereferee_server.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 결과가 끝내 도착하지 않은 요청을 정리하는 기준.
 *
 * 늦게 정리하는 비용은 ERROR 통보가 늦어지는 것뿐이지만, 일찍 정리하면 살아 있는 검증을
 * 실패로 못박는다. 그래서 실측치보다 넉넉하게 잡는다.
 * 실측 기준: 정상 완주 72초, 패치 거부 1.4초, 3라운드 최악 ~290초.
 * AI 측 샌드박스 작업 상한이 15~20분이므로 그보다 여유를 둔다.
 */
@ConfigurationProperties(prefix = "codereferee.validation")
public record ValidationTimeoutProperties(Duration queuedTimeout, Duration runningTimeout) {

    public ValidationTimeoutProperties {
        // 대기열은 샌드박스가 동시 1건만 처리해 길어질 수 있으므로 진행 중보다 느슨하게 둔다.
        queuedTimeout = queuedTimeout != null ? queuedTimeout : Duration.ofMinutes(60);
        runningTimeout = runningTimeout != null ? runningTimeout : Duration.ofMinutes(30);
    }
}
