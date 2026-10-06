# codereferee-server

CodeReferee 플랫폼의 Spring Boot 백엔드. 레포지토리 검증 요청을 수신해 requestId를 부여하고, Redis 큐로 AI 모듈에 전달하며, 결과 큐로 돌아온 진행 이벤트와 최종 리포트를 영속화한다. BE는 샌드박스와 K8s를 모르고, AI 모듈을 HTTP로 직접 호출하지 않는다 — 두 모듈 사이의 통로는 Redis 큐뿐이다.

## MVP Scope

- 레포지토리 검증 요청 수신 및 requestId 부여 (항상 서버 발급)
- Redis input 큐(`codereferee:workflow:input`)로 검증 요청 발행
- Redis output 큐(`codereferee:workflow:output`) 소비 — 진행 이벤트 반영 + 최종 판정 매핑
- 결과가 끝내 도착하지 않은 요청을 ERROR로 확정하는 안전망
- PostgreSQL 기반 TaskStatus UPSERT 영속화 및 재검사 이력 조회
- Prometheus 커스텀 메트릭 수집 파이프라인 (`/actuator/prometheus`)

## Quick Start

### 사전 요구사항

- Java 17
- PostgreSQL (**호스트 포트 5433** — `compose.yml`의 `codereferee-db`가 `5433:5432`로 노출한다)
- Redis (포트 6379) — 하나만 띄운다. brew redis와 Docker redis가 같이 6379를 잡으면 어느 쪽에 붙는지 모호해진다
- Docker (테스트가 Testcontainers로 Postgres + Redis를 기동한다)

AI 모듈은 기동 전제가 아니다. 큐로만 통신하므로 AI가 없어도 BE는 뜨고, 요청은 input 큐에 쌓인 채 기다린다.

### 로컬 실행

```bash
# 1. 의존 서비스 기동 (Postgres 5433 / Redis 6379)
docker compose up -d codereferee-db codereferee-redis

# 2. 스키마 수동 적용 (ddl-auto: none)
docker exec -i codereferee-db psql -U postgres -d codereferee < db/schema.sql

# 3. 서버 실행 — 접속 정보는 bootRun이 주입한다 (build.gradle)
./gradlew bootRun
```

AI 모듈 없이 전 구간을 돌려보려면 `mock-ai` 프로필로 기동한다. `MockAiWorker`가 AI 자리를 대신 이행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=mock-ai'
```

### 테스트

```bash
./gradlew test
```

**Docker가 필요하다.** E2E 테스트가 Testcontainers로 Postgres(운영과 같은 `db/schema.sql` 적용) + Redis를 띄워 제출→큐→상태 갱신→폴링/이력 전 구간을 관통한다.

### 전체 스택 실행 (서버 + DB + Redis + Prometheus + Grafana)

대시보드까지 한 번에 보려면 compose로 띄운다. 여기서도 `mock-ai`가 필요하다. 이 프로필이 없으면
요청이 input 큐에 그대로 머물러, 대시보드에 제출과 큐 적체만 보이고 판정도 소요시간도 나오지 않는다.

```bash
SPRING_PROFILES_ACTIVE=mock-ai docker compose up -d --build
```

- 서버: http://localhost:8080
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin / admin) → **CodeReferee 백엔드 운영**

스키마는 `ddl-auto: none`이라 수동이지만, `db/schema.sql`이 Postgres 초기화 스크립트로 들어가 있어
빈 데이터 디렉터리로 처음 뜰 때 자동 적용된다. 이미 데이터가 있으면 건너뛰므로 직접 적용한다.

```bash
docker exec -i codereferee-db psql -U postgres -d codereferee < db/schema.sql
```

대시보드에 값을 채워 보려면 세 경로를 모두 넣는다. mock 워커는 URL에 `fail`/`infra`가 들어가면
각각 FAILED/ERROR 시나리오로 응답한다.

```bash
for u in QuickByte_Demo fail-demo infra-demo; do
  curl -s -X POST http://localhost:8080/api/validations/repository \
    -H 'Content-Type: application/json' \
    -d "{\"repository_url\":\"https://github.com/phdcoco/$u\",\"branch\":\"main\"}"
done
```

### 모니터링 대시보드

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
    "branch": "main",
    "commit_sha": "a1b2c3d",
    "chaos_mode": "pod_delete",
    "deployment_profile": "fixture-api"
  }'
# 202 Accepted: {"requestId": "uuid"}
```

**요청 필드**

| 필드 | 필수 | 설명 |
|---|---|---|
| `repository_url` | ✅ | 검증할 레포지토리 |
| `branch` | | 브랜치 |
| `commit_sha` | | 커밋. 이력 조회 키로 쓰인다 |
| `chaos_mode` | | 카오스 실험 선택. 소문자·숫자·밑줄, 64자 이내 |
| `deployment_profile` | | 배포 프로필. 소문자·숫자·하이픈, 64자 이내 |

카오스 옵션의 의미는 BE가 모른다. 어떤 실험이 존재하는지는 샌드박스가 알고 유효한 조합인지는 AI가 판정하므로, BE는 **형식만** 보고 그대로 전달·보존한다. 형식 위반은 400 `VALIDATION_FAILED`로 돌려준다.

requestId는 항상 서버가 발급한다. 클라이언트가 보낸 식별자는 받지 않는다 (위조·중복 방지).

### 검증 상태 조회

```bash
curl http://localhost:8080/api/validations/{requestId}
```

없는 requestId는 404.

**TaskStatus 응답 필드**

| 필드 | 설명 |
|---|---|
| `taskId` | 요청 식별자 |
| `currentAgent` | 현재 단계 (아래 상태 머신) |
| `isExecutable` | 샌드박스 실행 가능 여부. PASSED일 때만 true |
| `iterationCount` | Refine 라운드 |
| `errorMessage` | 실패·오류 사유 |
| `createdAt` | 접수 시각. 전이마다 덮어쓰이지 않으므로 요청 전체 소요시간의 기준이 된다 |
| `updatedAt` | 마지막 전이 시각 |
| `repositoryUrl` · `branch` · `commitSha` | 요청 원본 |
| `chaosOptions` | 요청받은 `mode` / `deploymentProfile` 그대로 |
| `aiReports` | AI 모듈이 보낸 리포트. `preflight_report` · `execution_result` · `judge_report` · `critic_feedback` · `refiner_report` · `validation_plan` · `metrics` · `events` 중 값이 있는 것만 담긴다 |

**상태 머신 (AgentStep)**

```text
QUEUED → PREFLIGHT → BASELINE → CHAOS → JUDGING → (REFINING → BASELINE ...) → PASSED | FAILED | ERROR
```

- `PASSED` / `FAILED` — 코드에 대한 판정. FAILED는 Critic/Refiner 루프를 거친 최종 실패다.
- `ERROR` — 인프라·파이프라인 오류로 판정 불가. 코드 결함이 아니므로 Critic 분석 대상이 아니다.

### 재검사 이력 조회

```bash
curl "http://localhost:8080/api/validations/history?repository_url=https://github.com/CodeReferee-Team/codereferee-AI&commit_sha=a1b2c3d"
```

같은 레포 + 커밋의 과거 검증을 최신순 20건까지 돌려준다. 재검사 시 이전 리포트와 로그를 확인하는 용도다.

## Runtime Flow

```text
POST /api/validations/repository
  -> RefereeService: requestId 발급 → TaskStatus.queued 저장 (Redis + Postgres)
  -> RedisValidationRequestQueue: input 큐에 InputMessage RPUSH
     == 여기서 AI 모듈이 BLPOP으로 집어간다 ==
  -> ResultQueueConsumer: output 큐를 BLPOP하는 데몬 루프
     -> type=progress : 단계 전이 반영 (종결된 요청에 늦게 온 progress는 무시)
     -> type=result   : status → PASSED / FAILED / ERROR 매핑 + aiReports 저장
  -> StaleValidationSweeper: 끝내 결과가 없는 요청을 주기적으로 ERROR로 확정
  -> GET /api/validations/{requestId} 로 상태 폴링
```

리포트는 Postgres에 영속화한 뒤 유저에게 노출한다. Redis는 전달 통로이자 진행 중 상태의 저장소일 뿐이다.

## Configuration

| 파일 | 용도 | Git 포함 |
|---|---|---|
| `application.yml` | 기본 설정 | ✅ |
| `db/schema.sql` | task_status DDL (`ddl-auto: none`이라 수동 적용) | ✅ |
| `application-secret.yml` | DB 자격증명. `spring.profiles.include: secret`으로 로드된다 | ❌ |
| `application-local.yml` | 로컬 오버라이드. `local` 프로필을 활성화해야 읽힌다 | ❌ |
| `gradle.properties` | JVM 옵션 | ❌ |

접속 정보는 실행 경로마다 다른 곳에서 온다. `./gradlew bootRun`은 Postgres(5433) / Redis 접속 정보를 systemProperty로 주입하고(`build.gradle`), 컨테이너로 띄울 때는 `compose.yml`의 `SPRING_*` 환경변수가 같은 역할을 한다.

미결 요청을 ERROR로 확정하는 임계값은 `application.yml`에서 설정한다. 늦게 정리하면 ERROR 통보가 늦어질 뿐이지만 일찍 정리하면 살아 있는 검증을 실패로 못박으므로, 실측치보다 넉넉하게 잡는다.

```yaml
codereferee:
  validation:
    sweep-interval: 1m    # 미결 요청 점검 주기
    queued-timeout: 60m   # QUEUED 허용 시간 (샌드박스가 동시 1건만 처리해 대기열이 길어진다)
    running-timeout: 30m  # 진행 중 단계 허용 시간 (AI 측 작업 상한 15~20분보다 여유)
```

Actuator는 Prometheus 엔드포인트만 노출한다.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus
```

## Repository Layout

DDD 패키지 레이어링 (2026-08). 의존 방향은 `api` → `application` → `domain` ← `infrastructure`이며, 멀티 모듈 승격에 대비해 패키지 경계를 유지한다.

```text
src/main/java/.../
  api/
    RefereeController.java             # API 진입점 (제출 / 폴링 / 이력)
    RepositoryValidationRequest.java   # 요청 DTO, 카오스 옵션 형식 검증
    GlobalExceptionHandler.java        # 에러 응답 통일
    ErrorResponse.java
  application/
    RefereeService.java                # 요청 처리. domain port에만 의존
    StaleValidationSweeper.java        # 미결 요청을 ERROR로 확정
    ValidationTimeoutProperties.java   # 임계 시간 바인딩
  domain/validation/
    TaskStatus.java                    # 검증 상태 (불변 record)
    AgentStep.java                     # 상태 머신
    ChaosOptions.java                  # 카오스 옵션 값 객체
    TaskStatusRepository.java          # Port — 진행 중 상태
    TaskStatusHistoryRepository.java   # Port — 이력·리포트
    ValidationRequestQueue.java        # Port — AI 모듈 전달
  infrastructure/
    redis/
      RedisValidationRequestQueue.java # input 큐 RPUSH (도메인 → 큐 계약 변환)
      ResultQueueConsumer.java         # output 큐 BLPOP 데몬 루프
      TaskStatusRedisRepository.java   # 진행 중 상태 저장
      InputMessage.java                # 큐 계약 DTO (BE → AI)
      ProgressEventMessage.java        # 큐 계약 DTO (type=progress)
      SandboxResultMessage.java        # 큐 계약 DTO (type=result)
      RedisConfig.java                 # RedisTemplate JSON 직렬화
    persistence/
      TaskStatusPgRepository.java      # task_status UPSERT + 이력·미결 조회
    metrics/
      PipelineMetrics.java             # 카운터 + Timer
      QueueDepthMetrics.java           # 큐 적체 Gauge
    mock/
      MockAiWorker.java                # mock-ai 프로필 전용. AI 모듈 대역
src/main/resources/
  application.yml
db/schema.sql        # task_status DDL
compose.yml          # server + postgres + redis + prometheus + grafana
prometheus.yml       # Prometheus 스크레이프 설정
grafana/             # 데이터소스 + 대시보드 프로비저닝
Dockerfile
```

## Tech Stack

| 구성 요소 | 기술 |
|---|---|
| 프레임워크 | Spring Boot 3.5.3 / Java 17 |
| 영속화 | PostgreSQL + JdbcTemplate |
| 진행 중 상태 · 메시지 큐 | Redis (Spring Data Redis) |
| 메트릭 | Micrometer + Prometheus |
| 모니터링 | Grafana |
| 테스트 | JUnit 5 + Testcontainers + Awaitility |

## 큐 계약 v3 (2026-09)

| 방향 | 키 | 방식 |
|---|---|---|
| BE → AI (작업) | `codereferee:workflow:input` | RPUSH / BLPOP (List) |
| AI → BE (진행·결과) | `codereferee:workflow:output` | **RPUSH / BLPOP (List)** — pub/sub 아님 |

pub/sub은 BE가 내려가 있는 동안 도착한 메시지가 유실되므로 List를 쓴다. List는 BE가 재시작해도 큐에 남아 장애 시점의 리포트까지 보존된다.

output 큐 메시지는 `type` 필드로 구분한다. **`type`이 없으면 `result`로 간주한다** (v2 하위 호환).

| `type` | 내용 | 스키마 |
|---|---|---|
| `progress` | 단계 전환마다 오는 중간 이벤트. `step`은 AgentStep의 비종결 값이고, REFINING이면 `round` / `max_rounds`가 함께 실려 온다. BE는 폴링 응답에 즉시 반영하되 **종결된 요청에 늦게 온 progress는 무시**한다. 유실은 허용 — 다음 이벤트와 최종 결과가 따라잡는다 | `ProgressEventMessage` |
| `result` | 최종 결과, 요청당 1건 | `SandboxResultMessage` |

결과 메시지 `status` 값: `success`(PASSED) · `fail`(FAILED, 코드 결함) · `error`/`infra_error`(ERROR, 판정 불가 — Critic 루프 제외). 그 밖의 값은 FAILED로 떨어진다.

AI 모듈이 준비되기 전까지는 `MockAiWorker`(`mock-ai` 프로필)가 이 계약의 AI 쪽을 대신 이행한다. 레포지토리 URL에 `fail`이 들어 있으면 FAILED, `infra`가 들어 있으면 ERROR 시나리오를 재현한다.
