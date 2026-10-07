package com.codereferee.codereferee_server.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 카오스 옵션은 형식만 본다. 어떤 실험이 유효한지와 조합 제약은 샌드박스 어휘라서
 * AI가 판정한다 (확정 아키텍처: "BE는 K8s를 모른다").
 *
 * 다만 deployment_profile은 샌드박스에서 profiles/{name}.json 경로 조회에 쓰이므로
 * 형식 검증이 어휘 문제가 아니라 안전 속성이다. 샌드박스와 같은 패턴으로 한 겹 더 막는다.
 */
public record RepositoryValidationRequest(
        @NotBlank @JsonProperty("repository_url") String repositoryUrl,
        String branch,
        @JsonProperty("commit_sha") String commitSha,

        @Size(max = 64)
        @Pattern(regexp = "[a-z0-9_]+", message = "chaos_mode는 소문자, 숫자, 밑줄만 쓸 수 있습니다")
        @JsonProperty("chaos_mode") String chaosMode,

        @Size(max = 64)
        @Pattern(regexp = "[a-z0-9-]+", message = "deployment_profile은 소문자, 숫자, 하이픈만 쓸 수 있습니다")
        @JsonProperty("deployment_profile") String deploymentProfile,

        // 선택. 입력하면 완료 시 이 주소로 PDF 리포트를 보낸다. 형식만 본다.
        @Size(max = 254)
        @Email(message = "email 형식이 올바르지 않습니다")
        @JsonProperty("email") String email
) {}
