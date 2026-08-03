package com.codereferee.codereferee_server.referee;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record SandboxResultMessage(
        @JsonProperty("request_id")    String requestId,
        @JsonProperty("job_id")        String jobId,
        @JsonProperty("status")        String status,
        @JsonProperty("repository_url") String repositoryUrl,
        @JsonProperty("branch")        String branch,
        @JsonProperty("commit_sha")    String commitSha,
        @JsonProperty("validation_plan")  Map<String, Object> validationPlan,
        @JsonProperty("preflight_report") Map<String, Object> preflightReport,
        @JsonProperty("execution_result") Map<String, Object> executionResult,
        @JsonProperty("judge_report")     Map<String, Object> judgeReport,
        @JsonProperty("critic_feedback")  Map<String, Object> criticFeedback,
        @JsonProperty("refiner_report")   Map<String, Object> refinerReport,
        @JsonProperty("metrics")          Map<String, Object> metrics,
        @JsonProperty("events")           List<String> events
) {}
