package com.codereferee.codereferee_server.domain.validation;

import java.util.List;

// 리포트와 이력을 영속화하는 Port
public interface TaskStatusHistoryRepository {
    void upsert(TaskStatus status);

    List<TaskStatus> findByRepositoryAndCommit(String repositoryUrl, String commitSha);
}
