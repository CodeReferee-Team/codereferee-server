package com.codereferee.codereferee_server.domain.validation;

import java.time.LocalDateTime;
import java.util.List;

// 리포트와 이력을 영속화하는 Port
public interface TaskStatusHistoryRepository {
    void upsert(TaskStatus status);

    List<TaskStatus> findByRepositoryAndCommit(String repositoryUrl, String commitSha);

    /**
     * 결과가 끝내 도착하지 않은 요청들. 마지막 갱신이 임계 시각보다 오래된 비종결 상태를 찾는다.
     * 대기열에 밀려 있는 QUEUED와 실제로 진행 중인 단계는 허용 시간이 다르므로 임계 시각을 따로 받는다.
     */
    List<TaskStatus> findStale(LocalDateTime queuedBefore, LocalDateTime runningBefore, int limit);
}
