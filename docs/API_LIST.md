# Mormi 백엔드 API 목록 및 구현 현황

프로토타입(Mormi-FE)으로 실제 대상자 테스트를 하기 위한 API 전체 목록입니다.

## 0. 서비스 구성

| 레포 | 역할 | 상태 |
|---|---|---|
| `Mormi-FE` | Next.js 16 화면 + BFF | 화면 완성. **서버 연동 완료** |
| `Mormi-AI` | FastAPI + LangGraph 대화 | 완성 (8개 엔드포인트) |
| `Mormi-BE` | Spring Boot 학습 기록·인증된 AI 프록시 | 구현 완료 |

```text
브라우저
  → Next.js /api/be
    → Spring Boot: 인증·학습 기록·진행도·보상·대화 소유권
      → Mormi-AI: AI 대화 턴·도움 카드·별노트
```

---

## 1. Mormi-BE (Spring Boot) — 구현 완료

인증: `Authorization: Bearer <learner token>`.
보상 계산, 정오 판정, 해금 판정은 **전부 서버가 확정**합니다. 프런트는 표시만 합니다.

### A. 학습자

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/v1/learners` | — | 온보딩. 이름 + 참여 번호로 생성 또는 복구 |
| `POST` | `/v1/learners/auth` | — | 참여 번호로 복구, 토큰 재발급 |
| `GET` | `/v1/learners/{learner_id}` | ✓ | 프로필 조회 (본인만) |
| `PATCH` | `/v1/learners/me/conversation-consent` | ✓ | 자유 발화 암호화 저장 동의·보존기간 변경 |

```jsonc
// POST /v1/learners
{ "display_name": "민준", "research_code": "MORMI-A03" }
// 201
{
  "id": 1, "display_name": "민준", "research_code": "MORMI-A03",
  "analytics_id": "4fc04095-...",   // PostHog identify 전용 가명 ID
  "conversation_storage_consent": true, "retention_policy": "permanent",
  "access_token": "KMw_gyMdWRtm..."
}
```

- **같은 참여 번호로 다시 들어오면 새 학습자를 만들지 않고 기존 진행도를 이어받습니다.** 기기 교체·캐시 삭제 후에도 복구됩니다.
- `display_name` 은 화면 표시 전용. PostHog 와 AI 프롬프트에는 `analytics_id` 만 씁니다.
- 토큰은 평문 저장하지 않고 SHA-256 해시만 보관합니다.

### B. 진행도 / 해금

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/v1/progress` | 앱 시작 시 1회. 화면 상태 통째로 복구 |
| `GET` | `/v1/themes` | 장소별 해금 상태와 남은 필수 세션 |

```jsonc
// GET /v1/progress
{
  "learner_id": 1, "display_name": "민준", "analytics_id": "...",
  "onboarding_complete": true,
  "completed_session_ids": ["money-count", "money-price"],
  "wallet_balance": 7850,
  "level": 1, "stars": 6,
  "cafe_unlocked": false,
  "cafe_required_session_ids": ["number-count","number-compare","money-count","money-price","money-budget"],
  "active_learning_session_id": null,
  "active_cafe_visit_id": null
}
```

`level`·`stars`·`cafe_unlocked` 는 프런트가 계산하던 값을 서버로 옮긴 것입니다.

### C. 학습 세션 (집)

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/v1/learning-sessions` | 세션 시작 |
| `GET` | `/v1/learning-sessions/{id}` | 새로고침 복구 (시도 전체 포함) |
| `POST` | `/v1/learning-sessions/{id}/attempts` | 문제 시도 1건 |
| `POST` | `/v1/learning-sessions/{id}/teaching` | 저장된 반복 결과로 AI 가르치기 대화 시작·복구 |
| `POST` | `/v1/learning-sessions/{id}/complete` | 종료 + 보상 정산 |

```jsonc
// POST /v1/learning-sessions
{ "curriculum_session_id": "money-count", "variant_seed": 1284 }
```
`variant_seed` 는 필수입니다. 프런트가 `varyProblem()` 으로 문제를 런타임 생성하므로 seed 없이는 아이가 실제로 본 문제를 재구성할 수 없습니다.

```jsonc
// POST .../attempts  — 정답·오답 모두 보낸다
{
  "activity": "drill", "attempt_no": 1,
  "item_id": "money-count:0", "question_index": 0,
  "is_correct": false, "elapsed_ms": 4200,
  "answer_meta": {
    "selected_choice_id": "c2",
    "locked_choice_ids": [],
    "misconception_tag": "coin_count_not_value"
  }
}
// 200
{ "attempt_id": 1, "duplicate": false, "reward_granted": 0,
  "session_reward_subtotal": 0, "correct_count": 0,
  "mastery_target": 5, "drill_completed": false }
```

- 멱등키 `(learning_session_id, activity, attempt_no)`. 재전송하면 `duplicate: true` 로 첫 결과를 그대로 돌려주고 보상을 다시 주지 않습니다.
- **보상은 클라이언트 값을 쓰지 않고, 서버가 저장된 오답 수를 세어 등급을 정합니다.**
- `answer_meta`는 선택지 ID·오개념 태그·사다리 상태 같은 허용된 구조 데이터만 저장합니다. 아이 자유 발화, 모르미 질문, 정답 문장은 폐기합니다.

| 정답 전 오답 수 | 보상 |
|---:|---:|
| 0개 | 200원 |
| 1개 | 150원 |
| 2개 | 100원 |
| 3개 이상 | 50원 |

드릴 최대 1,000원 (5문제 × 200원), 가르치기 성공 고정 500원, 지갑 시작 6,000원.

```jsonc
// POST .../complete
{ "transfer_solved": true,
  "timed_out": false, "scaffold_level": 3, "elapsed_seconds": 142 }
// 200
{ "drill_reward": 850, "teach_reward": 500, "total_reward": 1350,
  "wallet_balance": 7350, "teach_reward_eligible": true,
  "practice_result_id": "practice_...",
  "completed_session_ids": [...], "cafe_unlocked": true }
```

가르치기 대화는 `.../teaching` 호출 때 BE가 생성하고 세션에 귀속합니다. 완료 요청이 임의의 `conversation_id`를 지정할 수 없으며, BE가 저장한 대화의 `completion.teach_reward_eligible`만 확인합니다. 보상 멱등키는 세션당 하나인 `teach-reward:{session}`입니다.

`.../teaching`은 정답 처리된 서로 다른 반복 문제 5개가 모두 저장된 뒤에만 성공합니다. 시도에서 `PracticeSummary`를 집계하고, 결정형 `practice_result_id`와 인라인 요약을 AI에 전달한 뒤 최초 전체 `TurnContract`를 반환합니다. 같은 요청을 다시 보내면 기존 대화를 복구합니다.

### D. 카페

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/v1/cafe-visits` | 방문 시작 (해금 검증, 진행 중 방문 있으면 이어받음) |
| `GET` | `/v1/cafe-visits/{id}` | 진행 복구 (시도 전체 포함) |
| `POST` | `/v1/cafe-visits/{id}/queue` | 줄 서기 (짧은 줄 인원수) |
| `POST` | `/v1/cafe-visits/{id}/menu` | 메뉴 2개 (예산 안) |
| `POST` | `/v1/cafe-visits/{id}/payments` | 메뉴값 계산 (두 메뉴 합계) |
| `POST` | `/v1/cafe-visits/{id}/change` | 거스름돈 |
| `POST` | `/v1/cafe-visits/{id}/complete` | 완료 |
| `POST` | `/v1/cafe-visits/{id}/dialogues` | 현재 카페 단계의 AI 대화 시작·복구 |

`stage = queue | menu | calculate | change | complete`. 다음 돌다리 해금은 서버가 판정합니다. 해금 전 방문은 403.

네 단계(줄 서기·메뉴·계산·거스름돈)의 **문제는 화면이 방문마다 새로 뽑습니다**. 좌우 인원, 예산,
계산·거스름돈에 쓰이는 메뉴가 매번 달라지므로 요청에 문제를 함께 싣고, 정오는 서버가 판정합니다.
최초 대화의 구조 맥락은 `dialogue_conversations.scenario_context`에 저장합니다. 같은 스테이지를
다시 열면 BE가 최초 맥락을 `scenario_context`로 돌려주므로, 새로고침 뒤 화면 숫자와 AI가
기억하는 숫자가 달라지지 않습니다.

```jsonc
// POST .../queue   정답은 min(left,right)
{ "left_count": 4, "right_count": 2, "chosen_count": 2,
  "scaffold_used": false, "attempt_no": 1 }

// POST .../menu    budget 은 8000 | 9000 | 10000 만 허용
{ "menu_ids": ["americano", "cookie"], "budget": 8000, "attempt_no": 1 }

// POST .../payments   두 메뉴값의 합을 아이가 적어 낸다
{ "menu_ids": ["strawberry-cake", "sandwich"], "answer_amount": 9000, "attempt_no": 1 }
// 200
{ "stage": "calculate", "is_correct": false,
  "next_stage": "calculate", "next_stage_unlocked": false,
  "attempts": 1, "expected_amount": 9500, "submitted_amount": 9000,
  "difference": -500, "feedback_code": "payment_short" }

// POST .../change   기대값 = 10,000 − menu_id 가격
{ "menu_id": "americano", "counts": { "1000": 6, "500": 2 }, "attempt_no": 1 }
```

- 메뉴 합계는 클라이언트 값이 아니라 **서버 가격표**로 계산합니다. 가격표는 `CafeJourney.tsx` 와 같아야 합니다.
- 화폐별 최종 구성만 저장하고 −/＋ 버튼 클릭 로그는 저장하지 않습니다.
- 예산 초과 주문도 **오답으로 기록**합니다(`menu_over_budget`). 다음 단계는 열리지 않습니다.

### E. 인증된 AI 대화 프록시

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/v1/dialogue/conversations/{conversation_id}` | 소유권 확인 후 최신 TurnContract 복구 |
| `POST` | `/v1/dialogue/conversations/{conversation_id}/responses` | 소유권 확인 후 아이 응답 전달 |

운영 호출 경로는 `FE → Spring BE → Mormi-AI`입니다. FE는 `learner_id`, 저장 동의, 보존기간, AI 서비스 키를 보내지 않습니다. Spring BE가 인증된 학습자 레코드에서 채웁니다.
카페 대화 응답에는 AI의 `conversation_id`, `turn`과 함께 BE가 보관한
`scenario_context`, 현재 단계 동기화 결과인 `stage_progress`가 포함됩니다.
`stage_progress.completed=true`는 기존 정답 시도가 이미 저장됐거나, AI 대화 완료의
`completion.verified_facts`를 Spring이 다시 검증해 카페 시도로 기록했다는 뜻입니다.
아이 원문이나 모르미 대사만으로는 단계를 진행시키지 않습니다.

```json
{
  "stage_progress": {
    "stage": "queue",
    "completed": true,
    "next_stage": "menu",
    "source": "dialogue_verified_facts"
  }
}
```

### F. 리포트

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/v1/reports/summary` | 최신 완료 세션 리포트 |
| `GET` | `/v1/reports/history?limit=8` | 세션 이력 |

`ReportDashboard.tsx` 의 `Report` 타입과 같은 필드 구성입니다. `sessionTitle`·`misconception`·`learnedLine` 처럼 커리큘럼 본문에 있는 값은 `session_id` 로 프런트가 채웁니다.

### G. 운영

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/health` | 상태 확인 (인증 불필요) |

---

## 2. Mormi-AI — 이미 구현 완료

`X-Mormi-Service-Key` 헤더 인증. 브라우저나 Next.js가 아니라 Spring BE만 호출합니다.

| Method | Path |
|---|---|
| `GET` | `/health` |
| `POST` | `/v1/practice-results` |
| `POST` | `/v1/conversations` |
| `POST` | `/v1/conversations/{conversation_id}/responses` |
| `GET` | `/v1/conversations/{conversation_id}` |
| `GET` | `/v1/learners/{learner_id}/skill-profiles` |
| `GET` | `/v1/learners/{learner_id}/star-notes` |
| `GET` | `/v1/conversations/{conversation_id}/transcript` |

`response_id` 멱등과 409 + `state_version` 복구가 구현돼 있습니다. 네트워크 타임아웃은
AI에서 처리가 끝났지만 응답만 유실된 경우도 있으므로, FE는 같은 답을 같은
`response_id`로 재전송하고 먼저 최신 턴을 조회합니다.

---

## 3. Mormi-FE 연동 원칙

연동 완료:
- 온보딩에서 이름 + 참여 번호 → `POST /v1/learners` → 토큰 보관
- 부팅 시 `GET /v1/progress` 로 상태 복구
- 드릴 정답·오답 전건 `POST .../attempts`
- 세션 종료 `POST .../complete` → 지갑·완료목록·해금을 서버 응답으로 갱신
- 카페 전 스테이지 기록 + 방문 복구
- PostHog `identify` 는 `analytics_id` 만 사용

- 실제 집·카페 화면은 Spring BE의 대화 프록시가 돌려준 전체 `TurnContract`를 렌더링합니다.
- 개발용 `/api/mormi/*` 직접 BFF는 `/ai-test` 전용이며 운영 학습자 화면에서 사용하지 않습니다.
- FE는 BE 시도 저장과 AI 턴이 모두 성공하기 전에 다음 단계를 성공으로 표시하지 않습니다.

---

## 4. DB 스키마 (`V1__init.sql`)

```
learners             id, display_name, research_code UNIQUE, analytics_id UUID UNIQUE,
                     token_hash UNIQUE, conversation_storage_consent, retention_policy,
                     onboarding_completed_at, created_at
learning_sessions    id, public_id UNIQUE, learner_id, curriculum_session_id, variant_seed,
                     scaffold_level, elapsed_seconds, transfer_solved, timed_out,
                     conversation_id, practice_result_id, started_at, completed_at
attempts             id, learning_session_id, activity, attempt_no, item_id, question_index,
                     is_correct, elapsed_ms, reward_granted, answer_meta JSONB, created_at
                     UNIQUE(learning_session_id, activity, attempt_no)
theme_progress       id, learner_id, theme_id, unlocked_at, completed_at
                     UNIQUE(learner_id, theme_id)
cafe_visits          id, public_id UNIQUE, learner_id, stage, target_amount,
                     order_total, paid_amount, change_amount, started_at, completed_at
cafe_visit_stages    id, cafe_visit_id, stage, attempt_no, is_correct, elapsed_ms,
                     payload JSONB, created_at
                     UNIQUE(cafe_visit_id, stage, attempt_no)
reward_ledger        id, learner_id, learning_session_id, source, amount,
                     idempotency_key UNIQUE, created_at
dialogue_conversations id, conversation_id UNIQUE, learner_id,
                     learning_session_id 또는 cafe_visit_id, scenario_id,
                     scenario_context JSONB, created_at
```

- 지갑 잔액은 별도 컬럼 없이 `reward_ledger` 합계로 도출합니다.
- 아동 데이터 테이블에는 `learner_id` 인덱스가 있습니다.
- 아이 이름·자유 발화 원문·음성은 저장하지 않습니다. 대화 원문은 Mormi-AI 가 암호화 보관합니다.

---

## 5. 실행

```bash
# DB
docker run -d --name mormi-db -e POSTGRES_DB=mormi -e POSTGRES_USER=mormi \
  -e POSTGRES_PASSWORD=mormi -p 5432:5432 postgres:16-alpine

# 백엔드
SPRING_PROFILES_ACTIVE=dev DB_HOST=localhost DB_PORT=5432 DB_NAME=mormi \
DB_USERNAME=mormi DB_PASSWORD=mormi ./gradlew bootRun

# 테스트 (Testcontainers, Docker 필요)
./gradlew test
```

환경 변수:

| 이름 | 용도 | 기본값 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | RDS 접속 | — |
| `CORS_ALLOWED_ORIGINS` | 프런트 오리진 | `http://localhost:3000` |
| `MORMI_DIALOGUE_BASE_URL` | Mormi-AI 주소. 비어 있으면 가르치기 500원 미지급 | 빈 값 |
| `MORMI_DIALOGUE_SERVICE_KEY` | Mormi-AI 서비스 키 | 빈 값 |

프런트 브라우저는 코드에 고정된 같은 출처 `/api/be`만 사용하고, Next 서버의
비공개 `BACKEND_ORIGIN`에 배포된 Spring BE 주소를 넣습니다.

---

## 6. 확정 필요한 사항

| # | 항목 | 현재 처리 |
|---|---|---|
| 1 | RDS 안에서 Spring / Mormi-AI 데이터 분리 | **미정.** Mormi-AI 는 `create_schema()` 로 직접 테이블을 만들고 Spring 은 Flyway + `ddl-auto: validate` 라 같은 스키마에 두면 충돌 위험. 스키마 또는 DB 분리 권장 |
| 2 | 지갑 vs 카페 10,000원 | 분리 유지. 카페는 고정 실습 소지금이고 지갑에서 차감하지 않음 |
| 3 | `ladder` 0~3 ↔ `L4~L0` 매핑 | 리포트는 0~3 그대로 저장. 대화 연동 시 확정 필요 |
| 4 | 별노트 저장 주체 | Mormi-AI 보유. Spring 은 TurnContract를 전달 |
| 5 | `conversation_storage_consent` 관리 주체 | Spring `learners` 및 동의 변경 API. AI가 실제 암호화·삭제 수행 |
| 6 | 참여 번호 발급 방식 | 연구자가 사전 발급해 전달하는 것으로 가정 |
