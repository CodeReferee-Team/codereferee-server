package com.codereferee.codereferee_server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 제출 → input 큐 → MockAiWorker → output 큐 → 상태 갱신 → 폴링/이력까지
 * 전 구간을 진짜 Redis + Postgres(Testcontainers)로 관통하는 E2E 테스트
 * MockAiWorker가 AI 모듈 자리를 대신하므로 mock-ai 프로필로 기동한다
 */
@Testcontainers
@ActiveProfiles("mock-ai")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockE2ePipelineTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            // 운영과 동일한 스키마 파일을 컨테이너 초기화 스크립트로 그대로 사용 (중복 정의 방지)
            .withCopyFileToContainer(MountableFile.forHostPath("db/schema.sql"),
                    "/docker-entrypoint-initdb.d/schema.sql");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:alpine")
            .withExposedPorts(6379);

    @Autowired
    TestRestTemplate rest;

    @Test
    void successRepositoryEndsPassed() {
        String requestId = submit("https://github.com/phdcoco/QuickByte_Demo");

        Map<String, Object> status = awaitTerminal(requestId);

        assertThat(status.get("currentAgent")).isEqualTo("PASSED");
        assertThat(status.get("isExecutable")).isEqualTo(true);
        assertThat(status.get("errorMessage")).isNull();
        Map<String, Object> reports = reports(status);
        assertThat(reports).containsKeys("preflight_report", "execution_result", "judge_report");

        // 리포트는 Postgres에 영속화된 뒤 이력으로도 조회돼야 한다 (source of truth 검증)
        ResponseEntity<List> history = rest.getForEntity(
                "/api/validations/history?repository_url={u}&commit_sha={c}",
                List.class, "https://github.com/phdcoco/QuickByte_Demo", "d1c5c5e");
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).anySatisfy(row ->
                assertThat(((Map<?, ?>) row).get("taskId")).isEqualTo(requestId));
    }

    @Test
    void failRepositoryEndsFailed() {
        String requestId = submit("https://github.com/phdcoco/always-fail-demo");

        Map<String, Object> status = awaitTerminal(requestId);

        assertThat(status.get("currentAgent")).isEqualTo("FAILED");
        assertThat(status.get("isExecutable")).isEqualTo(false);
        assertThat(reports(status)).containsKey("critic_feedback");
        assertThat(status.get("iterationCount")).isEqualTo(3);
    }

    @Test
    void infraProblemEndsErrorNotFailed() {
        String requestId = submit("https://github.com/phdcoco/infra-broken-demo");

        Map<String, Object> status = awaitTerminal(requestId);

        // 인프라 오류는 코드 결함(FAILED)과 반드시 구분되어야 한다.
        assertThat(status.get("currentAgent")).isEqualTo("ERROR");
        assertThat((String) status.get("errorMessage")).contains("판정 불가");
    }

    private String submit(String repositoryUrl) {
        Map<String, String> body = Map.of(
                "repository_url", repositoryUrl,
                "branch", "main",
                "commit_sha", "d1c5c5e");

        ResponseEntity<Map> response = rest.postForEntity("/api/validations/repository", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String requestId = (String) response.getBody().get("requestId");
        assertThat(requestId).isNotBlank();
        return requestId;
    }

    // 폴링 API를 실제 유저처럼 반복 호출해 종결 상태(PASSED/FAILED/ERROR)까지 대기한다.
    private Map<String, Object> awaitTerminal(String requestId) {
        AtomicReference<Map<String, Object>> last = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            ResponseEntity<Map> response = rest.getForEntity("/api/validations/" + requestId, Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            last.set(body);
            assertThat((String) body.get("currentAgent")).isIn("PASSED", "FAILED", "ERROR");
        });
        return last.get();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reports(Map<String, Object> status) {
        Map<String, Object> reports = (Map<String, Object>) status.get("aiReports");
        assertThat(reports).isNotNull();
        return reports;
    }
}