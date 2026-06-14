# codereferee-server

CodeReferee 플랫폼의 Spring Boot API 게이트웨이 서버. 외부 클라이언트로부터 레포지토리 검증 요청을 수신하고, 요청 식별자를 부여한 뒤 Redis 큐를 통해 비동기 파이프라인으로 발행하며, AI Core 서버에 검증을 위임한다.

## MVP Scope

- 레포지토리 검증 요청 수신 및 requestId 부여
- Redis 큐를 통한 Draft 에이전트 비동기 발행
- AI Core 서버(`/v1/validations/repository`) 연동 및 Fail-safe 처리
- PostgreSQL 기반 TaskStatus UPSERT 영속화
- Prometheus 커스텀 메트릭 수집 파이프라인 (`/actuator/prometheus`)

## Quick Start

### 사전 요구사항

- Java 17
- PostgreSQL (포트 5432)
- Redis (포트 6379)
- [AI Core 서버]([../codereferee-ai/README.md](https://github.com/CodeReferee-Team/codereferee-AI/blob/main/README.md)) 실행 중 (포트 8000)

### 로컬 실행

```bash
# 1. 로컬 설정 파일 생성 (DB 접속 정보 입력)
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# 2. 서버 실행
./gradlew bootRun
```

### 모니터링 스택 실행 (Prometheus + Grafana)

```bash
docker compose up -d
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin / admin)

## API

### 검증 요청 제출

```bash
curl -X POST http://localhost:8080/api/validations/repository \
  -H "Content-Type: application/json" \
  -d '{
    "repository_url": "https://github.com/CodeReferee-Team/codereferee-AI",
    "branch": "main"
  }'
# Response: {"requestId": "uuid"}
```

### 검증 상태 조회

```bash
curl http://localhost:8080/api/validations/{requestId}
```

**TaskStatus 응답 필드**

| 필드 | 설명 |
|---|---|
| `taskId` | 요청 식별자 |
| `currentAgent` | 현재 단계 (`DRAFT` / `SANDBOX` / `FAILED`) |
| `isExecutable` | 샌드박스 실행 가능 여부 |
| `iterationCount` | 반복 횟수 |
| `errorMessage` | 실패 사유 (실패 시) |
| `aiReports` | Judge / Critic / Refiner 결과 |

## Runtime Flow

```text
POST /api/validations/repository
  -> requestId 부여 & TaskStatus(DRAFT) 저장
  -> Redis 큐 발행 (DraftTaskMessage)
  -> AI Core 연동 (AiCoreClient, timeout 30s)
     -> 타임아웃 / 파싱 오류 시 TaskStatus(FAILED) Fail-safe 처리
  -> GET /api/validations/{requestId} 로 상태 폴링
```

## Configuration

| 파일 | 용도 | Git 포함 |
|---|---|---|
| `application.yml` | 기본 설정 | ✅ |
| `application-secret.yml` | DB/외부 시스템 자격증명 | ❌ |
| `application-local.yml` | 로컬 환경 오버라이드 | ❌ |
| `gradle.properties` | JVM 옵션 | ❌ |

AI Core 서버 주소는 `application.yml`의 `ai.core.base-url`로 설정한다.

```yaml
ai:
  core:
    base-url: http://127.0.0.1:8000
    repository-validation-path: /v1/validations/repository
```

## Repository Layout

```text
src/main/java/.../
  config/
    AiCoreConfig.java       # RestClient 빈 설정 (타임아웃 30s)
    AiCoreProperties.java   # AI Core 접속 정보 바인딩
  referee/
    RefereeController.java              # API 진입점
    RefereeService.java                 # 요청 처리 및 AI Core 위임
    AiCoreClient.java                   # AI Core HTTP 클라이언트 래퍼
    TaskStatus.java                     # 검증 상태 도메인
    TaskStatusPgRepository.java         # PostgreSQL UPSERT 레포지토리
    RepositoryValidationRequest.java    # 요청 DTO
    RepositoryValidationResponse.java   # AI Core 응답 DTO
    DraftTaskMessage.java               # Redis 큐 메시지
src/main/resources/
  application.yml
docker-compose.yml   # Prometheus + Grafana 모니터링 스택
prometheus.yml       # Prometheus 스크레이프 설정
```

## Tech Stack

| 구성 요소 | 기술 |
|---|---|
| 프레임워크 | Spring Boot 3.5.3 / Java 17 |
| 영속화 | PostgreSQL + JdbcTemplate |
| 메시지 큐 | Redis (Spring Data Redis) |
| HTTP 클라이언트 | RestClient (JDK HttpClient) |
| 메트릭 | Micrometer + Prometheus |
| 모니터링 | Grafana |
