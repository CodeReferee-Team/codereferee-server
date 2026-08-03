package com.codereferee.codereferee_server.domain.validation;

import java.util.Optional;

// 진행 중 상태를 저장하는 Port
public interface TaskStatusRepository {
    void save(TaskStatus status);

    Optional<TaskStatus> findById(String taskId);
}
