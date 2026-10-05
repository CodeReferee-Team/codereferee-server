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
- [AI Core 서버](https://github.com/CodeReferee-Team/codereferee-AI) 실행 중 (포트 8000)

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

데이터소스와 대시보드는 `grafana/`에서 자동 프로비저닝된다. Grafana를 켜면 **CodeReferee 백엔드 운영**
대시보드가 이미 들어 있다. UI에서 고친 내용은 컨테이너를 다시 만들 때 사라지므로, 수정은
`grafana/dashboards/codereferee-backend.json`에서 한다.

#### 노출하는 지표

| 지표 | 종류 | 태그 | 무엇을 보는가 |
|---|---|---|---|
| `codereferee_submissions_total` | Counter | — | 접수량 |
| `codereferee_verdicts_total` | Counter | `result` | 판정 분포. ERROR 비중이 인프라 신호 |
| `codereferee_agent_transitions_total` | Counter | `from`, `to` | 상태 전이. REFINING 전이가 많으면 패치 루프를 자주 돈다 |
| `codereferee_validation_duration_seconds` | Timer | `result` | 접수→종결 소요시간. p95가 여기서 나온다 |
| `codereferee_stage_duration_seconds` | Timer | `stage` | 단계별 체류시간. 어느 단계가 지연을 만드는지 |
| `codereferee_queue_depth` | Gauge | `queue` | 큐 적체. input은 AI 워커, output은 BE 소비 루프 |
| `codereferee_sweeper_swept_total` | Counter | `from` | 결과를 못 받아 ERROR로 확정한 건수 |

값이 0일 수 있는 조합은 미리 등록해 둔다. 아직 한 건도 없을 때 대시보드에 "No data"가 뜨면
"지표가 안 들어온다"와 "아무 일도 없었다"가 화면에서 구분되지 않기 때문이다.

`codereferee_queue_depth`는 Redis를 읽지 못하면 0이 아니라 NaN을 보고한다. 0으로 보고하면
"큐가 비었다"는 거짓이 된다.

> 샌드박스의 컨테이너·노드 지표(cAdvisor / Node Exporter)는 AI 인스턴스의 Prometheus에 모이므로
> 이 대시보드 범위가 아니다. 수집 배선은 `codereferee-sandbox` 레포의 `docs/metrics-collection.md` 참조.

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

## 큐 계약 v2 (2026-08)

| 방향 | 키 | 방식 |
|---|---|---|
| BE → AI (작업) | `codereferee:workflow:input` | RPUSH / BLPOP (List) |
| AI → BE (결과) | `codereferee:workflow:output` | **RPUSH / BLPOP (List)** — pub/sub 아님 |

> ⚠️ AI 모듈 변경 필요: 기존 `PUBLISH codereferee:workflow:output` → `RPUSH codereferee:workflow:output`.
> pub/sub은 BE가 내려가 있는 동안 도착한 결과가 유실되므로 List로 전환함 (장애 시점 리포트 보존 원칙).

결과 메시지 `status` 값: `success`(PASSED) · `fail`(FAILED, 코드 결함) · `error`/`infra_error`(ERROR, 판정 불가 — Critic 루프 제외)
