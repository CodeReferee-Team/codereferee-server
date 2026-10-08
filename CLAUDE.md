# CodeReferee Server (BE 모듈)

GitHub 레포를 샌드박스에서 실행·카오스 검증하는 SRE 플랫폼의 백엔드.
전체 시스템 = BE 모듈(이 레포, Spring Boot) + AI 모듈(FastAPI, 별도 레포) + K8s 샌드박스 클러스터(AI 모듈이 관리).

## 확정된 아키텍처 결정 (2026-08)

- **B안 확정**: K8s(k3s) + LitmusChaos. DooD/Pumba 안은 폐기.
- **샌드박스 관리 주체 = AI 모듈.** BE는 K8s를 모른다. BE 역할은 API + 큐 전달 + 영속화.
- **바운디드 컨텍스트 2개**: BE(검증 요청 관리) / AI(검증 실행). 샌드박스는 BC가 아니라 AI의 인프라.
- **배포 3대**: ①BE(t3.medium, compose) ②AI(t3.small, FastAPI+AI DB+Prometheus/Grafana) ③샌드박스(t3.large, k3s, Spot/필요시만 기동). 카오스 blast radius는 ③에 한정.
- **2단계 검증**: Preflight(AI, 실행 없음: URL·git ls-remote) → 샌드박스 Job Phase 1(clone→build→smoke=baseline) → 통과 시에만 Phase 2(LitmusChaos, 앱 컨테이너만 타겟, 실험 순차 60~120초).
- **패치 루프**: Refiner가 base commit 대비 누적 unified diff 생성(상한 1MB) → git apply --check 사전 검증 → 최대 3회 재검증(실패 실험만 + 마지막에 회귀 1회). 유저 레포에 push 금지, 최종 산출물은 "검증된 diff"를 리포트에 포함.
- **메트릭 (2026-10-05 개정)**: 샌드박스 안에서 **Prometheus Agent**(저장·질의 없는 수집 전용)가 cAdvisor/Node Exporter를 1초 간격으로 pull하고, 생성 즉시 AI 인스턴스의 Prometheus로 **remote_write**한다. Judge는 결과 JSON + 메트릭 요약으로 판정.
  - 원안은 "AI의 Prometheus가 샌드박스를 직접 scrape"였으나 **Ephemeral Sandbox 결정 이후 성립하지 않는다.** 요청마다 생겼다 사라지는 인스턴스라 `ec2_sd_config`의 발견 지연(기본 60초)이 베이스라인 구간을 통째로 먹고, 인스턴스 경계를 넘는 kubelet/k8s API 인증을 매번 유지해야 한다.
  - **저장소를 샌드박스에 두지 않는 것이 요구사항이다.** 카오스로 강제 종료될 수 있고 비용 때문에 인스턴스를 껐다 켠다. Agent는 디스크가 없어 죽어도 잃을 데이터가 없다.
  - **federation은 탈락.** `/federate`는 당겨가는 간격의 값만 가져와 다운샘플링된다. 카오스 구간 60초에 샘플 4개로는 스파이크가 안 보인다. 초 단위 해상도가 요구사항이다.
  - **Pushgateway와 다르다.** 수집은 여전히 pull(Agent→cAdvisor)이고, push는 장기 저장소로 넘기는 운반 구간에만 쓰는 Prometheus 표준 메커니즘이다.
- **DB 분리**: BE Postgres = 유저 대면(리포트·이력, source of truth) / AI DB(SQLite) = 내부(diff·원본 로그·라운드 상태). requestId로만 연결, 데이터 중복 금지.

## 배포 (2026-10-08 결정)

### `codereferee-infra` 레포를 둔다

레포 경계는 **소유**를, 배포 경계는 **런타임 제약**을 따른다. 둘은 일치하지 않으며 일치시키려고 도메인 코드를 옮기면 소유권이 흐려진다. "샌드박스는 BC가 아니지만 레포는 따로"와 같은 성격의 판단이다.

현재 세 레포의 compose는 전부 **로컬 개발용**이고 운영 토폴로지는 어디에도 없다. Prometheus가 세 레포에, Redis와 Postgres가 두 레포씩 중복 선언되어 있는 것은 각 레포를 혼자 띄울 수 있어야 하기 때문이며 정상이다. 비어 있는 것은 "EC2 ①번에 무엇이 뜨는가"를 적은 곳이다. `codereferee-infra`가 그 자리를 맡는다.

- 인스턴스별 운영 compose (BE용, AI용)
- 샌드박스 AMI 정의(Packer)와 user-data
- 보안그룹 정의
- 인스턴스별 환경변수 계약

각 레포의 로컬 compose는 그대로 둔다. 용도가 다른 파일이다.

### Prometheus는 AI 인스턴스 하나로 통합한다

운영에서는 AI 인스턴스의 Prometheus 하나가 두 역할을 겸한다.

- `scrape_configs`: BE actuator, AI 자신의 `/metrics`
- `--web.enable-remote-write-receiver`: 샌드박스 Agent의 1초 해상도 샘플 수신
- Grafana도 같은 인스턴스. 대시보드 정의는 `codereferee-server/grafana/provisioning/`에 두고 배포 시 가져다 쓴다. 메트릭을 내는 쪽이 대시보드를 소유하고 저장하는 쪽이 실행한다.

**샌드박스 레포는 코드 변경이 없다.** 수신기 주소가 이미 운영자 설정으로 빠져 있다. `CODEREFEREE_METRICS_REMOTE_WRITE_URL`과 `CODEREFEREE_METRICS_QUERY_URL`을 AI 인스턴스 주소로 바꾸면 된다. `install_observability.py`의 `--remote-write-url` 기본값(`host.docker.internal`)과 `infra/metrics-receiver/compose.yaml`은 로컬 개발용 대역으로 남는다.

따라오는 것:

- BE 보안그룹에 AI로부터의 8080 허용 (지금까지 BE는 Vercel만 받으면 됐다)
- 리텐션과 볼륨 크기 재산정. 1초 해상도라 로컬 대역의 24시간 설정을 그대로 쓸 수 없다
- **AI 인스턴스를 t3.small에서 t3.medium으로 상향.** Prometheus 하나가 BE scrape와 샌드박스 수신을 모두 받으면 2GB로는 부족하다

### 레포별 배치

| 인스턴스 | 올라가는 것 | 가동 |
|---|---|---|
| ① BE t3.medium | `codereferee-server`, Postgres, Redis | 상시 |
| ② AI t3.medium | `codereferee-AI`의 ai-core, SQLite, Prometheus(통합), Grafana | 상시 |
| ③ 샌드박스 t3.large Spot | `codereferee-sandbox`의 app/scripts/profiles/k8s, kind 클러스터 | 요청 시만 |
| Vercel | `codereferee-frontend` | 완료 |

Redis는 BE에 두고 AI가 네트워크로 붙는다. 샌드박스 서비스는 클러스터 안 Pod가 아니라 **호스트에서 직접 실행**한다. `app/main.py`가 kubectl을 subprocess로 부르고 `scripts/`와 `profiles/`가 디스크에 있어야 하며, `posix-bootstrap.md`의 기동 순서도 `source scripts/bootstrap_local.sh` 후 uvicorn이다.

### 배포 관련 미결

- **샌드박스 주소 발견**: ephemeral 인스턴스라 사설 IP가 매번 바뀌는데 AI의 `SANDBOX_BASE_URL`은 고정 설정값이다. boto3 기동(로드맵 17번)과 묶어서 설계해야 한다.
- **샌드박스 서비스 기동 방식**: user-data인지 systemd 유닛인지. 어느 쪽이든 `source`로 환경변수를 올린 뒤 **같은 셸에서** uvicorn을 띄워야 한다.
- **완전 WAL 드레인 증명**: `metrics_lifecycle.py`가 종료 후 20초간 수신 확인을 하고 `full_wal_drain_proven: False`로 정직하게 표시한다. Agent의 `prometheus_remote_storage_samples_pending`이 0이 될 때까지 기다리면 닫히지만, 판정 경로와 분리되어 있어 배포를 막을 사안은 아니다.
- **Argo는 쓰지 않는다**: server와 AI가 k8s 대상이 아니고, 샌드박스 클러스터는 ephemeral이라 GitOps 컨트롤러가 동기화를 마치기 전에 꺼진다. EKS는 컨트롤 플레인만 월 73달러로 예산의 3분의 1이라 제외.

## 상태 머신 (AgentStep)

QUEUED → PREFLIGHT → BASELINE → CHAOS → JUDGING → (REFINING → BASELINE ...) → PASSED | FAILED | ERROR

- **ERROR = 인프라 오류로 판정 불가.** FAILED(코드 결함)와 반드시 구분. ERROR는 Critic 루프를 타지 않는다.

## 큐 계약 v3 (Redis)

| 방향 | 키 | 방식 |
|---|---|---|
| BE → AI | `codereferee:workflow:input` | RPUSH / BLPOP (List) |
| AI → BE | `codereferee:workflow:output` | RPUSH / BLPOP (List) — **pub/sub 아님** (유실 방지) |

output 큐 메시지는 `type` 필드로 구분 (**`type` 없으면 `result`로 간주** — v2 하위 호환):

- `type: "progress"` — 단계 전환마다 push하는 중간 이벤트. 스키마는 `ProgressEventMessage` 참조 (`step`은 AgentStep 비종결 값과 일치, REFINING이면 `round`/`max_rounds`). BE는 폴링 응답에 즉시 반영하며, **종결된 요청에 늦게 온 progress는 무시**. progress는 유실 허용 (다음 이벤트·최종 결과가 따라잡음).
- `type: "result"` — 최종 결과, 요청당 1건. `status`: `success`→PASSED, `fail`→FAILED, `error`/`infra_error`→ERROR (**애매하면 error** — fail 오판이 더 나쁨). 스키마는 `SandboxResultMessage` 참조.

### Sandbox → AI 관측 계약 (2026-09-28 합의)

Sandbox 응답의 `observationStatus`가 "복구 실패"와 "관측 불가"를 가른다. `exitCode`만으로는 구분되지 않기 때문이다. **`timedOut`만으로 판단하지 않는다** — 같은 timedOut이라도 아래처럼 갈린다.

| observationStatus | exitCode | timedOut | 의미 | BE 최종 상태 |
|---|---|---|---|---|
| `observed` | 0 | - | 복구 성공 | PASSED |
| `observed` | 1 | - | 복구 실패 | FAILED |
| `observed` | 1 | true | 복구 관측 시간 초과 (코드 결함) | FAILED |
| `infrastructure_error` | null | - | Sandbox·k8s·kubectl·port-forward 문제로 관측 불가 | ERROR |
| `infrastructure_error` | null | true | Sandbox 실행 자체의 시간 초과 | ERROR |

HTTP 4xx는 요청 오류, 5xx는 구조화된 응답조차 만들 수 없는 예상 밖의 API 오류에만 쓴다. AI Core는 `infrastructure_error`를 기존 `infra_error` 흐름(`sandbox_observation_unavailable`)으로 넘겨 Judge/Critic/Refiner를 건너뛴다.

AI 파트가 아직 PUBLISH를 쓰고 있다면 RPUSH로 전환 필요.
AI 모듈이 준비되기 전까지는 `MockAiWorker`(mock-ai 프로필)가 이 계약의 AI 쪽을 대신 이행한다.

## 주요 코드 (DDD 패키지 레이어링, 2026-08)

의존 방향: `api` → `application` → `domain` ← `infrastructure`. 멀티 모듈 승격 대비 패키지 경계 유지.

- `api`: `RefereeController` — POST /api/validations/repository(서버가 requestId 발급), GET /{requestId}(폴링), GET /history?repository_url=&commit_sha=(재검사 이력)
- `application`: `RefereeService` — domain port에만 의존 (예외: PipelineMetrics는 크로스커팅으로 직접 의존 허용)
- `domain.validation`: `TaskStatus`/`AgentStep` + port 3개(`TaskStatusRepository`, `TaskStatusHistoryRepository`, `ValidationRequestQueue`)
- `infrastructure.redis`: `RedisValidationRequestQueue`(input 큐 RPUSH, 도메인→큐 계약 변환), `ResultQueueConsumer`(결과 큐 BLPOP 데몬 루프 → 상태 매핑 → Redis+PG 저장), 큐 계약 DTO(`InputMessage`, `SandboxResultMessage`)
- `infrastructure.persistence`: `TaskStatusPgRepository` — task_status upsert + 이력 조회. DDL은 `db/schema.sql` (ddl-auto: none, 수동 적용)
- `infrastructure.mock`: `MockAiWorker` — **mock-ai 프로필 전용.** AI 모듈 대역으로 input 큐 소비→가짜 결과 push (URL에 `fail`/`infra` 포함 시 FAILED/ERROR 시나리오)
- `infrastructure.metrics`: `PipelineMetrics` — 카운터(submissions, verdicts{result}, transitions{from,to}, sweeper.swept{from}) + Timer(validation.duration{result}, stage.duration{stage}). `QueueDepthMetrics` — 큐 적체 Gauge(queue=input/output)

## 명령어

- 테스트: `./gradlew test` — **Docker 필요** (E2E가 Testcontainers로 Postgres+Redis 기동)
- 로컬 스택: `docker compose up` (server + postgres + redis + prometheus + grafana)
- mock E2E 수동 확인: `SPRING_PROFILES_ACTIVE=mock-ai`로 서버 기동 후 제출→폴링
- **로컬 Postgres 호스트 포트 = 5433** (5432는 D:EAR 프로젝트 사용, 2026-08 변경). 스키마 수동 적용: `docker exec -i codereferee-db psql -U postgres -d codereferee < db/schema.sql`

## 다음 작업 (로드맵)

**완료 (2026-08~09):** DDD 패키지 레이어링 · mock E2E 파이프라인 · 상태 전이 세분화(progress 이벤트) · GitHub Actions CI · 전역 예외 처리 · 통합 샌드박스 이미지. AI 레포의 output 큐 push / `JobStatus.error` / SQLite 저장도 구현 완료.

### 역할 분담 (2026-09 확정)

- **인프라 (KAITOKIDDA)**: k3s 클러스터, 샌드박스 Job, LitmusChaos, cAdvisor/Node Exporter — `codereferee-sandbox` 레포
- **BE + AI (phdcoco)**: 이 레포 + `codereferee-AI` 레포 전체

### 실연동 1차 성공 (2026-09-27)

`mock-ai` 프로필 없이 실제 AI 워커와 연결해 **BE ↔ AI 전 구간 관통 확인**.

- 흐름: 제출 → input 큐 → AI 워커 소비 → Preflight/Planner/Sandbox/Judge/Critic/Refiner → progress(PREFLIGHT/BASELINE/JUDGING) + result push → BE가 FAILED 매핑 + aiReports 저장
- 큐 계약이 **와이어 수준에서 일치**함을 확인 (`taskId`/`repositoryUrl`/`branch`/`commitSha`/`submittedAt`)
- AI 레포(PR #74)에 output 큐 push, `JobStatus.error`, SQLite 저장, 인프라 오류 시 Judge/Critic/Refiner 스킵이 모두 구현됨 → 아래 "해야 할 일"의 블로커들은 해소됨

**드러난 갭과 해소 (같은 날):** Gradle 프로젝트를 `python:3.12-slim`에서 빌드하려다 `JAVA_HOME is not set`으로 실패했다. 통합 샌드박스 이미지(`ai-core/sandbox/Dockerfile`, temurin 17 + maven + python3 + node20)를 추가하고 리소스 제한을 실제 빌드가 가능한 값으로 올려 해결.

- 타임아웃 20s→600s, 메모리 128m→2g, CPU 0.5→2, pids 128→512. **세 가지를 같이 올려야 했다** — 실제 빌드가 62초 걸리고 JVM이 도므로 이미지만 바꿨다면 타임아웃에 걸렸다.
- 검증 스크립트도 수정: node 분기 실제 실행, maven 시스템 폴백, git 있으면 apt 생략(+`DEBIAN_FRONTEND=noninteractive`로 debconf 노이즈 8줄 제거).
- **결과: 동일 레포가 `PASSED` / `isExecutable: true` / `exit_code: 0`.** 폴링 중 `currentAgent: "BASELINE"`이 관측되어 progress 이벤트도 실시간 동작 확인.
- 스택 감지가 컨테이너 안(clone 이후)에서 일어나 실행 전 스택을 알 수 없으므로 통합 이미지(1.29GB)로 갔다. preflight가 스택을 먼저 감지하게 되면 스택별로 쪼갠다 — 회의록의 원래 설계.
- **후속:** 매 요청마다 Gradle 배포판을 다시 받아 62초 중 ~30초를 쓴다. `infra/sandbox/service`(HTTP 경로)는 이미 `GRADLE_USER_HOME=/cache/gradle` 등 캐시 볼륨을 쓰므로, 로컬 Docker 경로에도 같은 패턴 적용 필요.

**리포트 품질 문제 (실패 경로 한정, AI 레포에서 수정):** 성공 시에는 `judge_report.reason`이 정상 문장("Repository passed preflight and sandbox smoke validation.")으로 나온다. 아래는 fallback agent가 실패를 다룰 때만 발생한다.
- 동일 로그가 응답 하나에 **6번 중복** (`execution_result.stderr`, `judge_report.reason`/`evidence`, `critic_feedback.root_cause`/`evidence` ×2)
- fallback agent가 `reason`/`root_cause`에 로그 원문을 그대로 넣음 (한 문장이어야 할 자리)
- 샌드박스 엔트리포인트의 debconf 경고 8줄이 evidence에 섞임 (`DEBIAN_FRONTEND=noninteractive` 누락)
- `SandboxResult.log`에 빈 chaos 필드(`baseline={} metrics={} chaos_observation={} source={}`)가 항상 붙음 — PR #68 리뷰 지적사항 미반영

**로컬 환경 메모:**
- Redis는 반드시 하나만 띄운다. brew redis(`brew services stop redis`)와 Docker redis가 동시에 6379를 잡으면 어느 쪽에 붙는지 모호해진다.
- Postgres 데이터 디렉터리가 깨지면(`pg_multixact` 누락 등) `data/postgres` 삭제 → `docker compose up -d codereferee-db` → schema.sql 재적용.

### 작업 목록 (2026-09-27 전면 재정리)

확정 아키텍처·회의록·실연동 결과를 전부 대조해 다시 뽑았다. 프론트엔드는 최후로 미룬다.

#### 즉시

1. `multi_stack_sandbox_image` PR
2. BE 커밋 — `build.gradle`, `compose.yml` (CLAUDE.md는 당분간 untracked 유지)
3. `.gitignore`에 `.idea/`

#### 핵심 기능 — 프로젝트 가치 직결

4. **유저 레포 + 카오스 결합.** 경로가 갈라져 있다. Docker 경로(AI 레포)는 유저 레포를 빌드하지만 카오스가 없고, k8s 경로(샌드박스 레포)는 카오스를 걸지만 `fixture-api`만 본다. **"유저 코드에 장애를 걸어 SRE 관점으로 검증한다"가 아직 한 번도 실행된 적이 없다.** KAITOKIDDA와 "사용자 레포 자동 배포를 언제부터 범위에 넣을지" 합의가 선행.
5. ~~**샌드박스 결과 JSON 계약**~~ ✅ 2026-09-28 완료. 엔트리포인트가 마지막 줄에 결과 JSON 출력 + 단계별 exit code·소요 시간 기록 → Judge는 JSON + 메트릭 요약만으로 판정(로그 전문 불필요). 현재는 로그 텍스트를 통째로 Judge에 넘겨 리포트가 비대해지는 근본 원인. **8·9·12를 대부분 흡수한다.**
6. ~~패치 루프~~ → **AI 담당자 소유 (2026-09-28)**. AI 쪽에서 이미 구현해 1회차 실패 → 2회차 통과까지 확인했다고 보고. 위험 패치 차단(CI 설정 변경·레포 밖 파일·1MB 초과)도 포함. **BE/실행 레이어에서 중복 작업하지 말 것.** 잔여 의존: 샌드박스 요청에 `patchDiff` 칸이 없어 서비스 경로에서는 아직 못 쓴다(KAITOKIDDA 소관).
7. **메트릭 수집 파이프라인.** 확정 아키텍처의 메트릭 조항이 통째로 미구현이다 — cAdvisor·Node Exporter가 어디에도 없고(샌드박스 레포 48파일에 흔적 0), PromQL 질의 코드도 0줄이다. 그래서 샌드박스 응답의 `cpu_usage_percent`·`memory_usage_mb`가 항상 `null`이다.
   - ~~**(가) 수집 배선**~~ ✅ 2026-10-05 완료 (`codereferee-sandbox` PR `feat/metrics-collection-wiring`). cAdvisor + Node Exporter + Prometheus Agent를 `k8s/observability.yaml` 하나로, 설치는 `scripts/install_observability.py`.
     - **1초 해상도는 `scrape_interval`만으로 안 나왔다.** cAdvisor의 `allow_dynamic_housekeeping`이 기본 true라 한가한 컨테이너의 수집 주기를 최대 1분까지 늘리고(카오스 직전까지 조용한 컨테이너가 바로 스파이크를 낸다), `global_housekeeping_interval`도 기본 1분이라 pod-delete 이후 새 컨테이너를 1분간 발견하지 못했다. 셋 다 1초 고정.
     - cAdvisor가 샘플에 **자기 타임스탬프를 직접 박는데** scrape 간에 단조증가하지 않아 Prometheus가 out-of-order로 4,251개를 버렸다. `honor_timestamps: false`로 해결. 두 설정은 짝으로만 성립한다(주기가 늘어나면 같은 값이 반복 기록되어 스파이크가 평탄해진다).
     - **실측: 45초 구간 41샘플, 모든 간격 1.000초. 컨테이너 기동→최초 관측 1초. 10분 카오스 1회당 약 3 MiB**(활성 시계열 228개, 압축 5.2 KiB/s).
     - **수신 측 할 일**: AI 레포 `docker-compose.yml`의 prometheus에 `--web.enable-remote-write-receiver`가 없다. 없으면 404를 주는데 Agent가 실패가 아니라 **재시도**로 처리해서 조용히 큐만 쌓인다(`samples_failed_total`은 0인데 `enqueue_retries_total`만 증가).
   - **(나) Judge 연동** — AI 담당자. 수집된 메트릭을 판정 입력으로.
   - **(다) BE 운영 대시보드** — phdcoco. 아래 별도 항목.
   - **임계값은 데이터가 쌓인 뒤에 정한다.** 현재 `judge-policy.md`의 값(`cpu > 80`, `p95 > 300`)은 한 번도 측정해보지 않은 추정치다. 재서 쌓고 → 분포를 보고 → 기준을 정하는 순서.

#### 품질·안정성

8. ~~실패 경로 리포트 정리~~ ✅ 5번과 함께 완료 (Critic 프롬프트 18,872자 → 9,008자)
9. ~~`SandboxResult.log`의 빈 chaos 필드 제거~~ ✅ 5번과 함께 완료
10. ERROR 시 1회 재시도 — 회의록·다이어그램 명시, 코드 없음. `max_self_healing_retries`도 미사용
11. BE 타임아웃 안전망 — 실측 62초 확보로 임계값 산정 가능
12. 원본 로그 AI DB 저장 + 에이전트엔 발췌본만 (스택트레이스 ±N줄). SQLite에 로그 테이블 없음
13. null 메트릭(`cpu_usage_percent`/`memory_usage_mb`)과 judge policy `missing_metrics` 충돌 확인 — 7번이 되면 null이 사라져 자연 해소된다
14. ~~**BE 운영 대시보드 (다)**~~ ✅ 2026-10-06 완료 (BE PR `feat/ops-dashboard-metrics`). 소요시간·단계별 체류시간·큐 적체·스위퍼 정리 건수 추가, Grafana 데이터소스+대시보드(4행 14패널) 프로비저닝.
    - **`created_at` 칼럼을 추가했다.** `updated_at`은 전이마다 덮어쓰이므로 단계별 체류시간은 계산되지만 요청 전체 소요시간(p95)은 계산할 수 없었다. nullable이고 upsert의 `DO UPDATE`에서 갱신하지 않는다(접수 시각이 밀리면 소요시간이 0에 수렴). 값이 없는 옛 레코드는 `updated_at`으로 메우지 않고 집계에서 빠진다 — 메우면 조용히 틀린다.
    - **값이 0일 수 있는 조합은 미리 등록한다.** Micrometer는 첫 호출 때 카운터를 만들어서, 아직 한 건도 없으면 대시보드에 "No data"가 뜬다. 운영 화면에서 "지표가 안 들어온다"와 "아무 일도 없었다"는 전혀 다른 뜻인데 눈으로 구분되지 않는다.
    - **큐 길이 Gauge는 Redis 실패 시 NaN을 돌려준다.** 0은 "큐가 비었다"는 거짓이 되고, 예외는 `/actuator/prometheus` 응답 전체를 깨서 하필 장애 중에 다른 지표까지 못 보게 만든다.
    - 데이터소스 uid를 `codereferee-prometheus`로 고정했다. 자동 생성 uid는 컨테이너 재생성 시 바뀌어 모든 패널이 빈 화면이 된다.
    - 검증: mock-ai로 PASSED/FAILED/ERROR 세 경로를 돌려 16개 쿼리 전부가 **Grafana 경유로** 데이터를 돌려주는 것까지 확인. 테스트 28 → 39개.

#### 성능·보안

14. 툴체인 캐시 볼륨 — 62초 중 ~30초가 Gradle 재다운로드. `infra/sandbox/service` 패턴 재사용
15. 샌드박스 non-root 실행 — 회의록 "필수", 현재 root
16. preflight 스택 감지 → 스택별 이미지 분리 (통합 이미지 1.29GB 축소)

#### 운영·배포

17. boto3 샌드박스 기동/중지 (Ephemeral Sandbox)
18. EC2 3대 실배포 — BE t3.medium / AI t3.small / 샌드박스 t3.large Spot

#### 협업 대기

19. ~~샌드박스 PR #1 `observationStatus`~~ ✅ 2026-09-28 합의 + AI 쪽 구현 완료 (위 "Sandbox → AI 관측 계약" 참조)

#### 정리

20. 데이터셋 PR 67개 — 매일 쌓이는데 `datasets/codereferee/reviewed/`는 비어 있음. 자동 생성 지속 여부 판단
21. `docs/redis.md`·`docs/backend-ai.md` 내용 불일치, README의 "Spring Boot API gateway" 문구

#### 백로그 / 최후

22. Redis Streams 전환 (XADD/XREADGROUP, ACK, DLQ)
23. 프론트엔드

### AI 레포 소유 경계 (2026-09-28 정리 필요)

AI 레포를 둘이 함께 쓰면서 패치 루프가 중복될 뻔했다. 실제로 각자 해온 일을 기준으로 선을 그으면 이렇다.

- **AI 담당자**: 에이전트 레이어 — 판정 규칙, 프롬프트, 패치 루프, 평가 시스템, 데이터셋, 로컬 모델
- **phdcoco**: 실행 레이어와 경계 — 샌드박스 러너·이미지, 결과 계약, 큐 계약, BE 연동, 인프라

한 줄로: **"샌드박스에 무엇을 보내고 무엇을 받느냐"는 phdcoco, "받은 걸로 무엇을 판단하느냐"는 AI 담당자.**

**권장 순서 (2026-10-06 갱신):** 7번 (가)·(다) 완료. 남은 건 **(나) Judge PromQL 연동(AI 담당자)** 과, 수신 측 Prometheus에 `--web.enable-remote-write-receiver` 추가다. 다음 BE 작업은 **10번(ERROR 시 1회 재시도)** 또는 **4번(유저 레포 + 카오스 결합, KAITOKIDDA 합의 선행)**.

**이전 순서 (2026-09-28):** 5번 완료. 6번은 AI 담당자에게 이관. 다음은 **11번(BE 타임아웃 안전망)** — BE 단독이라 샌드박스 경로 결정(4번)에 영향받지 않고, 실측 62초로 임계값 근거도 확보됐다.

14번(툴체인 캐시)은 **보류 권장.** 현재 Docker 실행 경로 전용인데 4번에서 k8s 샌드박스로 갈아타면 그 경로를 안 쓰게 되어 버려질 수 있다. 4번 결정 이후에 판단한다.

## 주의

- 구 기획('자율 코드 생성', DRAFT 단계, BE의 샌드박스 직접 호출, AiCore 직접 HTTP)은 2026-08 리팩토링으로 제거됨. 부활시키지 말 것.
- requestId는 항상 서버 발급 (클라이언트 값 수신 금지).
- 리포트는 반드시 Postgres 영속화 후 유저 노출 (Redis는 전달 통로일 뿐).
