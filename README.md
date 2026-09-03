# Mormi BE

모르미 학습 서비스의 인증, 진도, 카페·놀이동산 진행, 보상, 그리고 **AI 대화의 소유권**을
관리하는 Spring Boot 백엔드입니다.

브라우저는 AI를 직접 호출하지 않습니다. 이 서버가 Bearer 토큰으로 학습자를 확인하고, DB에
저장된 대화 동의 정책을 붙인 뒤 서비스 키로 Mormi-AI를 호출합니다. AI가 발급한
`conversation_id`는 학습 세션 또는 방문과 함께 `dialogue_conversations`에 묶여, 다른 아이의
대화를 조회하거나 응답을 위조해 보상을 받는 경로를 막습니다.

## 시스템 안에서의 위치

```text
브라우저 (아동 · 교사)
   │  HTTPS
   ▼
Mormi-FE            Next.js 16 · Vercel
   │                /api/be/* 서버 전용 프록시
   │  Authorization: Bearer <학습자 토큰>
   ▼
Mormi-BE  ◀── 이 저장소
   │        Spring Boot 4 / Java 21 · EC2 + RDS PostgreSQL
   │        인증 · 진도 · 카페/놀이동산 판정 · 보상 · 대화 소유권 · 교사 리포트
   │  X-Mormi-Service-Key
   ▼
Mormi-AI            FastAPI + LangGraph · EC2 (같은 VPC)
                    발화 이해 · 사다리 결정 · 모르미 대사 → TurnContract
```

- 저장소: [Mormi-FE](https://github.com/flyai-y2s2/Mormi-FE) · [Mormi-BE](https://github.com/flyai-y2s2/Mormi-BE) · [Mormi-AI](https://github.com/flyai-y2s2/Mormi-AI)

## 이 저장소의 책임 경계

**한다**

- 학습자·교사 계정 생성·복구와 Bearer 토큰 인증, 기관·코호트·연구 코드
- 집 반복 문제의 정오·구조화 오개념·보상 원장 저장
- `POST /v1/learning-sessions/{id}/teaching` 한 번으로 반복 결과 집계 + AI 가르치기 시작
- AI `TurnContract` 전체를 변경 없이 FE로 전달
- 카페 세 단계(줄 서기 · 메뉴값 합산 · 거스름돈) 판정과 단계별 대화 생성, 새로고침 복구
  (메뉴 선택은 합산 문제를 구성하는 화면 준비 동작이지 별도 학습 스테이지가 아님)
- 놀이동산 방문·해금 관리 (문제·정답·힌트·전이 콘텐츠는 AI가 소유)
- AI가 도움 경로까지 완료하면 `completion.verified_facts`만 사용해 단계 기록을 동기화하고
  `stage_progress`를 FE에 반환
- 교사용 진단 리포트, 사다리 추천 승인, 관찰 이벤트 수집

**하지 않는다**

- 교육적 판단. 발화사다리·힌트사다리, 도움 카드, 별노트 생성은 전부 Mormi-AI의 몫입니다.
- AI 문장을 정답으로 신뢰하는 것. 모르미가 무슨 말을 했는지가 아니라, AI의 결정형
  오케스트레이터가 검증한 `verified_facts`만 진행 판정에 씁니다.
- 아이 자유 발화 원문 저장. 원문은 `attempts.answer_meta`나 `cafe_visit_stages.payload`에
  들어가지 않고, 응답 순간 AI로만 전달됩니다. 보관 여부는 AI가 동의 정책에 따라 결정합니다.

생활 스테이지 진행(`stage_completion_eligible`)과 아이 주도 가르치기 보상
(`teach_reward_eligible`)은 분리된 신호이고, 보상은 세션당 한 번만 지급합니다.

## 기술 스택

| 영역 | 사용 |
|---|---|
| 프레임워크 | Spring Boot 4.0 (Web MVC, Data JPA, Security, Validation, Actuator) |
| 언어 | Java 21 |
| DB | PostgreSQL 16 · Flyway 마이그레이션 21개 (`ddl-auto: validate`) |
| API 문서 | springdoc-openapi (`local` 프로필에서만 노출) |
| 테스트 | JUnit 5 · Testcontainers PostgreSQL |
| 빌드·배포 | Gradle → `app.jar` → Docker(temurin 21-jre-alpine) → ECR → EC2 |

JSON은 전역 `SNAKE_CASE`, `null` 필드는 응답에서 제외합니다. FE·AI와 계약 문서를 맞추기 위한
설정입니다.

## 빠른 시작

요구사항: Java 21, PostgreSQL 16.

```bash
SPRING_PROFILES_ACTIVE=dev \
DB_HOST=localhost DB_PORT=5432 DB_NAME=mormi \
DB_USERNAME=mormi DB_PASSWORD=mormi \
MORMI_DIALOGUE_BASE_URL=http://localhost:8000 \
MORMI_DIALOGUE_SERVICE_KEY=change-me \
./gradlew bootRun
```

상태 확인은 `GET /health`, `GET /actuator/health`입니다. 로컬(`local` 프로필)에서는
`/swagger-ui`와 `/v3/api-docs`가 열립니다. 배포 서버의 8080은 인터넷에 열려 있어
`dev` 프로필에서 springdoc을 끕니다.

## 환경 변수

| 이름 | 필수 | 기본값 | 설명 |
|---|:--:|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | ✅ | — | PostgreSQL 접속 정보 |
| `CORS_ALLOWED_ORIGINS` | | `http://localhost:3000` | 허용할 FE 오리진 |
| `MORMI_DIALOGUE_BASE_URL` | ✅ | 빈 값 | Mormi-AI 내부 주소. 비면 가르치기 보상을 지급하지 않음 |
| `MORMI_DIALOGUE_SERVICE_KEY` | ✅ | 빈 값 | AI의 `MORMI_SERVICE_API_KEY`와 같은 값 |
| `MORMI_DIALOGUE_READ_TIMEOUT_SECONDS` | | `45` | 분류기 + 화자 순차 호출 대기 시간 |
| `MORMI_OBSERVATION_INGEST_KEY` | | 빈 값 | AI → BE 관찰 이벤트 수신 키. 비면 `/internal/**` 은 전부 401 |
| `MORMI_LOCAL_REPORT_ADMIN_ENABLED` / `_KEY` | | `false` / 빈 값 | 교사 리포트 서버 간 조회. 키가 없으면 닫힘 |

키는 FE나 Vercel 공개 환경변수에 넣지 않습니다.

## 도메인과 API

| 패키지 | 컨트롤러 | 대표 경로 |
|---|---|---|
| `auth` | `AuthController` | `POST /v1/auth/signup` · `login` · `logout` · `educators/signup` |
| `learner` | `LearnerController` | `GET /v1/learners/{id}` · `PATCH /me/character-name` · `PATCH /me/conversation-consent` |
| `session` | `LearningSessionController` | `POST /v1/learning-sessions/{id}/attempts` · `complete` · `teaching` |
| `dialogue` | `DialogueController` | `POST /v1/dialogue/conversations/{id}/responses` · `GET /v1/dialogue/conversations/{id}` |
| `cafe` | `CafeController` | `POST /v1/cafe-visits/{id}/queue` · `menu` · `payments` · `change` · `complete` · `dialogues` |
| `amusementpark` | `AmusementParkController` | `POST /v1/amusement-park-visits/{id}/dialogues` |
| `progress` | `ProgressController` | `GET /v1/progress` · `themes` · `history` |
| `dictionary` | `DictionaryController` | `GET .../dictionary-card` (대화·학습세션 단위) |
| `starnote` | `StarNoteController` | `GET /v1/learners/{id}/star-notes` |
| `report` | `ReportController`, `DiagnosticReportController`, `LocalReportAdminController` | `GET /v1/reports/...` · `speech-evidence` · `diagnostic` |
| `organization` | `CohortController` | `GET /v1/cohorts/{id}/learners` · `reports` · `POST research-codes` |
| `observation` | `ObservationIngestController` | `POST /internal/v1/observations/events` (AI 전용, 공유 키) |

요청·응답 상세는 [`docs/API_LIST.md`](docs/API_LIST.md), 오류 코드는
[`docs/ERROR_CODES.md`](docs/ERROR_CODES.md), 스키마는 [`docs/ERD.md`](docs/ERD.md)를 참고하세요.

## 테스트

```bash
./gradlew test
```

통합 테스트는 Testcontainers로 PostgreSQL을 띄우므로 RDS 없이 동작하고, `test` 프로필로
실행됩니다. PR 단계에서도 같은 테스트가 돌아 `develop`이 깨진 채 남지 않도록 합니다.

## 배포

`develop` 브랜치에 푸시하면 GitHub Actions가 빌드·테스트 → Docker 이미지 → ECR → EC2 배포를
수행합니다. AWS 인증은 OIDC 역할이고, EC2 접근은 SSH 키입니다. 최초 세팅(ECR, IAM, EC2,
환경변수 파일, 방화벽)과 롤백 절차는 [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)에 있습니다.

## 문서

- [`docs/API_LIST.md`](docs/API_LIST.md) — 엔드포인트 요청·응답
- [`docs/ERD.md`](docs/ERD.md) — 테이블 구조
- [`docs/ERROR_CODES.md`](docs/ERROR_CODES.md) — 오류 계약
- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — AWS 배포·운영·롤백
- [`docs/mvp-data-api-design.md`](docs/mvp-data-api-design.md) — 데이터·API 설계 배경

AI 교육 로직, 발화사다리·힌트사다리, 도움 카드와 별노트 생성은
[Mormi-AI](https://github.com/flyai-y2s2/Mormi-AI)에서 관리합니다.
