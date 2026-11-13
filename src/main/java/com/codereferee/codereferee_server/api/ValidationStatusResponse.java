package com.codereferee.codereferee_server.api;

import com.codereferee.codereferee_server.domain.validation.TaskStatus;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 폴링 응답. TaskStatus를 그대로 펼치고 대기 순번만 덧붙인다.
 *
 * 필드를 하나하나 옮겨 담지 않고 @JsonUnwrapped를 쓰는 이유는, 옮겨 담으면 TaskStatus에
 * 필드가 늘 때마다 여기도 같이 고쳐야 하고 빠뜨리면 화면에서 값이 조용히 사라지기 때문이다.
 * 이 레포가 이미 겪은 실패 모양이다(refine_rounds가 BE에서 버려지던 건).
 *
 * 감싸는 대신 펼치므로 기존 JSON 모양이 그대로 유지되고 queuedAhead 하나만 늘어난다.
 */
public record ValidationStatusResponse(
        @JsonUnwrapped TaskStatus status,
        Integer queuedAhead
) {
    public static ValidationStatusResponse of(TaskStatus status, Integer queuedAhead) {
        return new ValidationStatusResponse(status, queuedAhead);
    }
}
