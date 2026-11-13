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

    /**
     * 이 요청보다 먼저 접수되어 아직 끝나지 않은 요청 수. 대기 순번을 보여주는 데 쓴다.
     *
     * 비종결 전체를 세는 이유는, 큐에 남아 있는 것과 지금 돌고 있는 것이 모두 내 앞이기
     * 때문이다. 샌드박스가 한 번에 한 건만 처리하므로(experiment_lock) 앞에 있는 비종결
     * 요청은 전부 내 차례 전에 끝나야 한다.
     *
     * 접수 시각이 없는 옛 레코드는 순서를 알 수 없으므로 세지 않는다. 추정해서 끼워 넣으면
     * 순번이 조용히 틀린다.
     */
    int countAheadOf(String taskId, LocalDateTime createdAt);
}
