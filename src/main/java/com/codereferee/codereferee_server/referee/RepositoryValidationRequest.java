package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RepositoryValidationRequest(
        @NotBlank @JsonProperty("repository_url") String repositoryUrl,
        String branch,
        @JsonProperty("commit_sha") String commitSha,
        @JsonProperty("request_id") String requestId
) {}
