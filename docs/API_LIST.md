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

인증: `Authorization: Bearer <access token>`.
보상 계산, 정오 판정, 해금 판정은 **전부 서버가 확정**합니다. 프런트는 표시만 합니다.

계정은 학생·교사 공용 `accounts` 하나입니다. **로그인은 하나로 합치고, 가입만 나눕니다.**
학습 경로(`/v1/learners` `/v1/learning-sessions` `/v1/cafe-visits`
`/v1/amusement-park-visits` `/v1/dialogue` `/v1/progress` `/v1/themes` `/v1/reports`)는
학생 토큰 전용, 학급 경로(`/v1/cohorts`)는
교사 토큰 전용이며 역할이 다른 토큰으로 부르면 403 입니다.

### A. 인증

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/v1/auth/signup` | — | 학생 회원가입. 201 + `access_token` |
| `POST` | `/v1/auth/educators/signup` | — | 교사 회원가입. 201 + `access_token` |
| `POST` | `/v1/auth/login` | — | 통합 로그인. 200 + `role` 로 도착지 분기 |
| `POST` | `/v1/auth/logout` | ✓ | 현재 기기의 토큰만 폐기. 204 |
| `POST` | `/v1/auth/logout-all` | ✓ | 해당 계정의 모든 토큰 폐기. 204 |

```jsonc
// POST /v1/auth/signup — 학생
{
  "display_name": "민준", "research_code": "MORMI-A03",
  "login_id": "minjun01", "password": "pilot1234"
}
// 201
{
  "id": 1, "display_name": "민준", "research_code": "MORMI-A03",
  "analytics_id": "4fc04095-...",   // PostHog identify 전용 가명 ID
  "conversation_storage_consent": true, "retention_policy": "permanent",
  "access_token": "KMw_gyMdWRtm..."
}

// POST /v1/auth/educators/signup — 교사. 기관 이름이 정확히 같으면 같은 기관에 합류
{
  "organization_name": "모르미초등학교", "display_name": "김교사",
  "position": "교사",                 // 교사 | 연구자
  "login_id": "teacher01", "password": "pilot1234"
}
// 201
{
  "id": 1, "display_name": "김교사", "position": "교사",
  "organization_id": 1, "organization_name": "모르미초등학교",
  "access_token": "wJq3xhJdiA..."
}

// POST /v1/auth/login — 학생·교사 공용
{ "login_id": "minjun01", "password": "pilot1234" }
// 200 — role 에 맞는 쪽(learner 또는 educator)만 채워진다
{
  "role": "learner",                 // learner -> /   educator -> /teacher/cohorts
  "access_token": "KMw_gyMdWRtm...",
  "learner": { "id": 1, "display_name": "민준", "research_code": "MORMI-A03", "...": "..." }
}
```

- `login_id` 는 영숫자 4~20자로 학생·교사 공용 전역 유니크, `password` 는 8자 이상입니다. 비밀번호는 BCrypt 해시만 보관합니다.
- **아이디가 없을 때와 비밀번호가 틀릴 때의 응답이 같습니다.** 가입 여부를 떠볼 수 없게 하기 위함이며, 프런트는 두 경우를 구분해 안내할 수 없습니다.
- **로그인해도 기존 토큰이 죽지 않습니다.** 태블릿과 보호자 휴대폰을 동시에 쓸 수 있습니다.
- 토큰은 평문 저장하지 않고 SHA-256 해시만 `auth_tokens` 에 보관합니다. 만료는 발급 30일이며 인증에 성공할 때마다 뒤로 밀립니다.
- `logout` 은 그 요청에 쓰인 토큰만, `logout-all` 은 해당 계정의 모든 토큰을 폐기합니다. 폐기된 토큰은 즉시 401 입니다.
- `research_code` 는 연구 식별자 전용이며 인증에 관여하지 않습니다. 교사가 사전 발급한
  참여 번호로 가입하면 그 학급에 자동 재적됩니다 (B-2 절).

### B. 학습자

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/v1/learners/{learner_id}` | ✓ | 프로필 조회 (본인만) |
| `PATCH` | `/v1/learners/me/conversation-consent` | ✓ | 자유 발화 암호화 저장 동의·보존기간 변경 |
| `GET` | `/v1/learners/{learner_id}/star-notes` | ✓ | 별노트 목록 (본인만). 상세는 G-3 |

- `display_name` 은 화면 표시 전용. PostHog 와 AI 프롬프트에는 `analytics_id` 만 씁니다.
- 구 연구 코드 온보딩(`POST /v1/learners`, `POST /v1/learners/auth`)은 **제거됐습니다.**
  V13 이전에 그 경로로만 온보딩한 학습자는 로그인 수단이 없어 접근이 끊기지만,
  행과 학습 기록은 연구 산출물로 남습니다.

### B-2. 학급 (교사 전용)

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/v1/cohorts` | 교사 | 학급 생성. `class_code` 는 서버가 발급 |
| `GET` | `/v1/cohorts` | 교사 | 내 기관의 학급 목록 |
| `POST` | `/v1/cohorts/{id}/research-codes` | 교사 | 참여 번호 사전 발급 (최대 50개) |
| `GET` | `/v1/cohorts/{id}/learners` | 교사 | 재적 중인 아이 목록 |
| `GET` | `/v1/cohorts/{id}/reports` | 교사 | 학급 리포트. `?from=&to=` 없으면 최근 7일 |

```jsonc
// POST /v1/cohorts
{ "name": "1반" }
// 201
{ "id": 1, "name": "1반", "class_code": "TQ7M3K", "organization_id": 1, "created_at": "..." }

// POST /v1/cohorts/1/research-codes
{ "codes": ["MORMI-A03", "MORMI-A04"] }
// 201 — learner_id 가 있으면 이미 가입한 아이가 소급 재적된 것
[ { "code": "MORMI-A03", "learner_id": 7 }, { "code": "MORMI-A04" } ]

// GET /v1/cohorts/1/learners
[ { "id": 7, "display_name": "민준", "research_code": "MORMI-A03", "enrolled_at": "..." } ]
```

- **아이 가입 폼은 그대로입니다.** 교사가 참여 번호를 미리 발급하고, 아이는 지금처럼 그 번호를
  입력합니다. 서버가 발급 장부(`cohort_research_codes`)에서 학급을 찾아 재적시킵니다.
- 모든 학급 API 는 요청 교사가 그 학급의 **기관 소속인지 검증**합니다. 다른 기관은 403.
- 이미 발급된 참여 번호를 다시 발급하면 409 `research_code_issued`.
- 학급 리포트 본문은 `report_snapshots` 집계(학습자 섹션 모음)이며 LLM 을 쓰지 않습니다.
  재적 중인 아이가 없으면 404.

### C. 진행도 / 해금

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

### D. 학습 세션 (집)

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/v1/learning-sessions` | 세션 시작 |
| `GET` | `/v1/learning-sessions/{id}` | 새로고침 복구 (시도 전체 포함) |
| `POST` | `/v1/learning-sessions/{id}/attempts` | 문제 시도 1건 |
| `POST` | `/v1/learning-sessions/{id}/teaching` | 저장된 반복 결과로 AI 가르치기 대화 시작·복구 |
| `GET` | `/v1/learning-sessions/{id}/dictionary-card` | 세션 커리큘럼의 궁금해사전 카드 조회 (F 절 참고) |
| `POST` | `/v1/learning-sessions/{id}/complete` | 종료 + 보상 정산 |

```jsonc
// POST /v1/learning-sessions
{ "curriculum_session_id": "money-count", "variant_seed": 1284 }
```
`variant_seed` 는 필수입니다. 프런트가 `varyProblem()` 으로 문제를 런타임 생성하므로 seed 없이는 아이가 실제로 본 문제를 재구성할 수 없습니다. 빠뜨리면 `validation_failed` (422) 로 `fields.variantSeed` 에 사유가 담겨 옵니다. 본문 자체가 깨졌거나 타입이 맞지 않으면 `invalid_request` (400) 입니다.

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

`.../teaching`은 정답 처리된 서로 다른 반복 문제 5개가 모두 저장된 뒤에만 성공합니다. 시도에서 `PracticeSummary`를 집계하고, 결정형 `practice_result_id`와 인라인 요약을 AI에 전달한 뒤 최초 전체 `TurnContract`를 반환합니다. body 없이(또는 `start_mode` 없이) 다시 보내면 마지막 회차 대화를 복구합니다.

```jsonc
// POST .../teaching  — body 는 선택
{ "start_mode": "restart", "request_id": "9f4c…(요청마다 새 UUID)" }
```

`start_mode: "restart"` 는 기존 대화를 보존한 채 새 회차(`round` 증가)의 새 대화를 만들어
첫 턴부터 시작합니다. 세션이 신뢰하는 대화(`learning_sessions.conversation_id`)는 항상
마지막 회차를 가리키고, 가르치기 보상 멱등키는 세션당 하나라 재시작해도 보상이 중복되지
않습니다. `request_id` 규칙은 카페 재시작(E 절)과 같습니다.

### E. 카페

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/v1/cafe-visits` | 방문 시작 (해금 검증, 기존 방문 있으면 이어받음) |
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
대화의 구조 맥락은 `dialogue_conversations.scenario_context`에 저장합니다. 같은 스테이지를
다시 열면 BE가 저장된 맥락을 `scenario_context`로 돌려주므로, 새로고침 뒤 화면 숫자와 AI가
기억하는 숫자가 달라지지 않습니다.

**재시작과 이어하기**: `POST .../dialogues` 는 `start_mode` 로 의도를 구분합니다(#22).

```jsonc
// POST .../dialogues
{
  "scenario_id": "cafe_queue",
  "queue_context": { "left_count": 4, "right_count": 1 },
  "start_mode": "restart",              // restart | resume
  "request_id": "9f4c…(요청마다 새 UUID)"  // 멱등키, 선택이지만 restart 에 권장
}
```

- `start_mode: "restart"` — 새로고침·다시 연습. 기존 기록은 분석용으로 보존한 채 새
  회차(`dialogue_conversations.round` 증가)의 새 `conversation_id` 를 만들고, 그때 화면이
  뽑은 새 문제를 그 회차의 `scenario_context` 로 고정합니다.
- `start_mode: "resume"` — 명시적 이어하기. 마지막 회차를 그대로 돌려줍니다(없으면 새로
  만듭니다).
- `request_id` — FE가 시작 요청마다 새로 뽑는 멱등키. 같은 요청이 네트워크 재시도로 중복
  도착하면 이미 만든 회차를 그대로 돌려주고, `(learner_id, request_id)` 유니크 제약이 중복
  INSERT 를 막습니다. 같은 `request_id` 를 다른 방문·시나리오에 재사용하면 409
  (`dialogue_request_id_conflict`)입니다.
- **폐기 예정**: 옛 `"restart": true|false` boolean 은 `start_mode` 가 없을 때만
  해석합니다(true→restart, false→resume). FE가 `start_mode` 로 전환을 마치면 제거합니다.

방문이 `complete` 여도 네 단계 모두 제출·대화가 열립니다. 진행도는 전진 전용이라 재시작이
`stage` 를 되돌리지는 않습니다.

```jsonc
// POST .../queue   정답은 min(left,right). 좌우 인원은 1~5 이고 서로 달라야 한다
{ "left_count": 4, "right_count": 2, "chosen_count": 2,
  "scaffold_used": false, "attempt_no": 1 }

// POST .../menu    budget 은 7000 | 8000 만 허용 (구버전 저장분 9000 | 10000 은 한시 허용)
{ "menu_ids": ["americano", "cookie"], "budget": 7000, "attempt_no": 1 }

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
- **문제 계약 위반은 채점이 아니라 4xx 거절**이고 시도 기록도 남지 않습니다. 줄 인원이
  1~5 를 벗어나거나(`queue_count_range`) 좌우가 같으면(`queue_count_equal`), 같은 메뉴 두 개를
  내면(`menu_duplicate`), 카탈로그에 없는 메뉴면(`menu_unknown`) 거절됩니다.
  코드는 `ERROR_CODES.md` 를 참고합니다.

`POST .../dialogues` 의 문제 컨텍스트는 타입 있는 계약으로 검증합니다. 줄 서기는
`queue_context`, 나머지 세 단계는 `cafe_context` 를 싣습니다. 계약 위반은 **AI 대화를
만들기 전에 400 으로 거절**하므로, 대화를 끝까지 진행한 뒤 완료 동기화에서 5xx 로
실패하는 일이 없습니다. 메뉴 ID·가격은 서버 카탈로그와 대조하고(`menu_unknown`,
`menu_price_mismatch`, `menu_items_duplicate`), `mormi_menu_id` 는 메뉴판 안에 있어야
하며(`mormi_menu_unknown`), `budget` 은 `cafe_budget_menu` 에서만 필수입니다(`budget`).

```jsonc
// POST .../dialogues  줄 서기
{ "scenario_id": "cafe_queue",
  "queue_context": { "left_count": 3, "right_count": 5 },
  "start_mode": "resume" }

// POST .../dialogues  메뉴·계산·거스름돈
{ "scenario_id": "cafe_budget_menu",
  "cafe_context": {
    "menu_items": [
      { "id": "americano", "name": "아메리카노", "price": 3000 },
      { "id": "cookie", "name": "쿠키", "price": 2000 }
    ],
    "mormi_menu_id": "americano",
    "budget": 8000
  },
  "start_mode": "restart",
  "request_id": "9f4c…(요청마다 새 UUID)" }
```

### E-2. 놀이동산 (이슈 #29)

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/v1/amusement-park-visits` | 방문 시작 (해금 검증, 기존 방문 있으면 이어받음) |
| `GET` | `/v1/amusement-park-visits/{id}` | 진행 복구 (스테이지 콘텐츠 + 시도 전체) |
| `POST` | `/v1/amusement-park-visits/{id}/stages/{stage_id}` | 단계 답 제출 |
| `POST` | `/v1/amusement-park-visits/{id}/complete` | 완료 |
| `POST` | `/v1/amusement-park-visits/{id}/dialogues` | 현재 놀이동산 단계의 AI 대화 시작·복구 |

`stage_id = ticket | snack_split | pass_break_even`. **카페를 완료해야 열립니다.** 해금 전 방문은 403.

카페와 두 가지가 다릅니다.

1. **콘텐츠를 서버가 내려줍니다.** 제목·미션·전략·모르미 오개념·전이 문장까지 방문 응답에
   담습니다. FE는 정답·설명문을 임의로 만들지 않고 이 값을 표시만 합니다.
2. **문제 숫자를 방문 시작 시 뽑아 고정합니다.** 가격과 인원은 방문을 시작할 때 서버가
   새로 뽑아 `amusement_park_visits.facts` 에 저장하고, 같은 `visit_id` 안에서는 바뀌지
   않습니다. 그래서 제출·대화 요청에 문제를 함께 싣지 않습니다(카페는 화면이 매번 새로 뽑아
   함께 보냅니다). **아래 예시의 숫자는 한 번 뽑힌 결과일 뿐이니 FE는 값을 하드코딩하지 말고
   응답을 그대로 표시하세요.** 출제 범위는 다음과 같습니다.

   | 단계 | 주어지는 값 | 범위 |
   |---|---|---|
   | `ticket` | `ticket_price` / `party_count` | 2,000~5,000원(천 원 단위) / 2~5명 |
   | `snack_split` | `snack_total` / `payer_count` | 1인당 1,000~3,000원 × 인원 / 2~5명 |
   | `pass_break_even` | `single_ride_price` / `day_pass_price` | 1,000~3,000원 / 1회 값 × 본전 3~6회 |

   `snack_total` 은 `payer_count` 로, `day_pass_price` 는 `single_ride_price` 로 **항상
   나누어떨어집니다.** 정답(1인당 낼 돈·본전 횟수)을 먼저 뽑고 문제를 곱셈으로 되돌려 만들기
   때문입니다. `transfer` 문장의 숫자도 이 방문 값에서 한 칸 올려(금액 +1,000원, 인원 +1명)
   만들므로 본문제와 절대 같지 않습니다.

```jsonc
// GET /v1/amusement-park-visits/{id}
{
  "theme_id": "amusement_park",
  "visit_id": "park_visit_…",
  "stage_order": ["ticket", "snack_split", "pass_break_even"],
  "stage_progress": {                 // locked | available | completed
    "ticket": "available", "snack_split": "locked", "pass_break_even": "locked"
  },
  "stages": [
    {
      "stage_id": "ticket",
      "scenario_id": "amusement_ticket_multiply",
      "title": "매표소", "mission": "우리 일행 표 사기", "skill": "multiply",
      "strategy": "같은 돈이 여러 번이면 곱하면 돼",
      "mormi_misconception": "표가 여러 장이어도 한 장 값만 내면 되는 줄 알았어.",
      "prompt": "1인 입장료와 일행 수를 이용해 총액을 설명해 주세요.",
      "facts": [                        // ← 값은 방문마다 새로 뽑힌다. 하드코딩 금지
        { "key": "ticket_price", "label": "1인 입장료", "value": 3000, "unit": "원" },
        { "key": "party_count",  "label": "우리 일행",  "value": 2,    "unit": "명" }
      ],
      "verified_facts": { "ticket_price": 3000, "party_count": 2, "total_price": 6000 },
      "transfer": {                     // ← 본문제에서 한 칸 올린 새 숫자
        "prompt": "그럼 1인 4,000원이고 3명이면?",
        "equation": "4,000 × 3 = 12,000",
        "conclusion": "4,000원을 3번 더한 것과 같으니까 12,000원이야!"
      }
    }
    // snack_split, pass_break_even …
  ],
  "attempts": [ /* 틀린 시도 포함 전체 */ ]
}
```

단계별 필수 `verified_facts` 키는 이슈 계약과 같습니다.

| 단계 | 주어지는 값 | 아이가 구하는 값 |
|---|---|---|
| `ticket` | `ticket_price`, `party_count` | `total_price` |
| `snack_split` | `snack_total`, `payer_count` | `per_person` |
| `pass_break_even` | `single_ride_price`, `day_pass_price` | `break_even_rides`, `benefit_from_rides` |

```jsonc
// POST .../stages/ticket   answers 에는 "아이가 구하는 값"만 담는다
{ "answers": { "total_price": 3000 }, "attempt_no": 1, "elapsed_ms": 4200 }
// 200
{ "visit_id": "park_visit_…", "stage": "ticket", "is_correct": false,
  "next_stage": "ticket", "next_stage_unlocked": false, "attempts": 1,
  "expected_answers": { "total_price": 6000 },
  "submitted_answers": { "total_price": 3000 },
  "feedback_code": "ticket_short" }

// POST .../stages/pass_break_even   답이 두 개인 단계는 둘 다 맞아야 통과
{ "answers": { "break_even_rides": 5, "benefit_from_rides": 6 }, "attempt_no": 1 }
```

- 주어진 값(`ticket_price` 등)을 `answers` 에 실으면 `answer_unknown` 400 입니다.
  정답은 서버가 방문에 고정된 값으로만 계산합니다.
- `feedback_code` 는 답이 하나인 단계에서 `{stage}_short` / `{stage}_over` 로 방향까지
  알려주고, 답이 둘인 단계는 `{stage}_wrong` 입니다. 정답은 `{stage}_correct`.
- 세 단계를 모두 통과하기 전 완료 요청은 `stage_incomplete` 409 입니다.
- 완료된 방문은 세 단계가 모두 다시 열립니다(연습 모드). 진행도는 전진 전용입니다.

```jsonc
// POST .../dialogues   문제 사실은 서버가 방문에서 꺼내므로 컨텍스트를 보내지 않는다
{ "scenario_id": "amusement_ticket_multiply",   // | amusement_snack_divide | amusement_pass_compare
  "start_mode": "resume",                       // restart | resume
  "request_id": "9f4c…(요청마다 새 UUID)" }
```

대화 완료 턴의 `completion.verified_facts` 는 카페와 같은 방식으로 단계를 통과시킵니다.
주어진 값이 방문에 고정된 값과 다르면 통과시키지 않고 `dialogue_completion_fact_mismatch`
503 으로 재시도를 유도합니다. 자유 발화 원문이나 모르미 대사는 절대 정답으로 쓰지 않습니다.

> ⚠️ **Mormi-AI 쪽 시나리오 핸들러가 아직 없습니다.** `amusement_*` 세 시나리오를 AI가
> 인식하고 `verified_facts` 를 위 키로 채워 주기 전까지 `POST .../dialogues` 는
> `dialogue_*` 오류로 실패합니다. 결정적 제출 경로(`POST .../stages/{stage_id}`)는
> AI 없이도 동작합니다.

### F. 인증된 AI 대화 프록시

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/v1/dialogue/conversations/{conversation_id}` | 소유권 확인 후 최신 TurnContract 복구 |
| `POST` | `/v1/dialogue/conversations/{conversation_id}/responses` | 소유권 확인 후 아이 응답 전달 |
| `GET` | `/v1/dialogue/conversations/{conversation_id}/dictionary-card` | 대화에 고정된 궁금해사전 카드 스냅샷 |

운영 호출 경로는 `FE → Spring BE → Mormi-AI`입니다. FE는 `learner_id`, 저장 동의, 보존기간, AI 서비스 키를 보내지 않습니다. Spring BE가 인증된 학습자 레코드에서 채웁니다.
카페 대화 응답에는 AI의 `conversation_id`, `turn`과 함께 BE가 보관한
`scenario_context`, 현재 단계 동기화 결과인 `stage_progress`가 포함됩니다.
`stage_progress.completed=true`는 **그 회차가** 이미 통과 기록을 남겼거나, AI 대화 완료의
`completion.verified_facts`를 Spring이 다시 검증해 카페 시도로 기록했다는 뜻입니다.
판정 기준은 방문 진행도가 아니라 회차입니다. 방문 진행도로 판정하면 이미 지난 단계를
다시 연습할 때 대화 검증 없이 무조건 통과가 되어 버립니다.
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

**고정 질문 앵커(`turn.task_anchor`)**: 아이가 지금 답해야 할 질문입니다. 모르미 대사는
도움 카드·브리지 반응으로 계속 바뀌므로 FE는 대사에서 원래 질문을 복원할 수 없습니다.
AI가 LLM이 아닌 결정적 규칙으로 계산해 매 턴 실어 보내고, **BE는 이 값을 읽지도 바꾸지도
않고 그대로 통과**시킵니다. 모르미 대사나 placeholder를 분석해 BE가 질문을 새로 만들지
않습니다. 지어낸 값은 화면에 그럴듯하게 뜨면서 AI 상태와만 어긋나므로 탐지되지 않습니다.

```json
{
  "turn": {
    "task_anchor": {
      "anchor_id": "cafe_queue:guided_count",
      "title": "지금 모르미에게 알려줄 것",
      "prompt": "사람을 한 명씩 눌러 두 줄을 같이 세어 볼까?",
      "completed_items": [
        { "slot_id": "left_count", "label": "왼쪽 줄 사람 수",
          "value": 3, "display_text": "왼쪽 줄에는 3명이 있어." }
      ],
      "target_slots": ["right_count"]
    }
  }
}
```

- **선택 필드입니다.** AI는 진행 중(`status=active`)이고 입력을 받는 턴에만 채웁니다.
  완료된 턴과 `input.kind=none` 인 턴에는 `null` 로 옵니다.
- 값이 **없는 모양이 두 가지**입니다. 신버전 AI 가 앵커를 뺀 턴은 `"task_anchor": null`,
  아직 배포되지 않은 구버전 AI 응답은 **키 자체가 없습니다.** BE는 둘을 뭉개지 않고
  받은 모양 그대로 넘깁니다. FE는 두 경우 모두 앵커 영역을 렌더링하지 않습니다.
- `completed_items` 는 아이가 이미 검증된 값으로 알려 준 항목입니다. AI 가 검증한 슬롯만
  들어가며, 자유 발화 원문은 들어가지 않습니다.
- **스트리밍 경로는 현재 BE 에 없습니다.** AI 에는
  `POST /v1/conversations/{id}/responses/stream` SSE 엔드포인트가 있고 앵커를
  `mormi.delta` 보다 **먼저** `turn.metadata { turn_id, task_anchor }` 이벤트로 흘립니다.
  대사가 흐르기 전에 질문이 화면에 고정되도록 한 순서입니다. 다만 BE 가 호출하는 AI 경로는
  `POST /v1/conversations`, `POST /v1/conversations/{id}/responses`,
  `GET /v1/conversations/{id}` 셋뿐이라 BE 프록시에는 스트리밍 구간이 없습니다. 나중에
  BE 가 스트림을 중계하게 되면 `turn.metadata` 를 이 순서 그대로 전달해야 합니다.
- 전 경로 무손실 전달은 `TaskAnchorContractIntegrationTest` 가 지킵니다. 대화 응답은
  상류 스키마를 복제하지 않으려고 `JsonNode` 로 투명 전달하므로 자바 타입이 없고,
  따라서 이 계약을 강제하는 것은 컴파일러가 아니라 그 테스트입니다.
- **이 경로의 응답은 `/v3/api-docs` 로 타입 생성을 하면 안 됩니다.** 자동 생성 명세가
  `GET /v1/dialogue/conversations/{id}` 와 `POST /v1/cafe-visits/{id}/dialogues` 는
  빈 `{"type":"object"}` 로, `POST /v1/learning-sessions/{id}/teaching` 은
  `JsonNode` 스키마로 내보내는데, 그 `JsonNode` 스키마의 속성은 실제 응답 필드가 아니라
  Jackson 내부 판별 메서드(`array`, `empty`, `null`, `nodeType` …)입니다. 이 경로의
  응답 형태는 AI 문서(`Mormi-AI/docs/API_SPEC.md`)의 `SessionEnvelope` 가 원본이고,
  BE 가 카페에서 덧붙이는 필드는 이 문서가 원본입니다.

**궁금해사전 중계**: 사전 카드는 AI가 소유한 승인·버전 관리 콘텐츠입니다. BE는
인증과 소유권만 확인하고 카드 본문(`reference` + `card`)을 **무손실로 통과**시킵니다.
문장을 보정하거나 자체 fallback 문구를 만들지 않으므로, 응답 스키마는 AI 문서
(`Mormi-AI/docs/API_SPEC.md`)가 원본입니다.

- 세션 경로는 현재 승인된 최신 카드, 대화 경로는 대화 시작 시점에 고정된 스냅샷을
  돌려줍니다. **가르치기 대화 중에는 대화 경로를 써야** 모르미의 설명과 사전 문장이
  같게 유지됩니다. 집·카페 대화 모두 같은 대화 경로 하나로 조회합니다.
- 세션 경로는 `?expected_content_version=N` 을 받으며 AI에 그대로 전달합니다.
  버전이 다르면 409 `dictionary_version_mismatch` 로 거절됩니다.
- 읽기 요청이라 연결 실패·AI 5xx 는 BE가 1회 재시도한 뒤 503 을 돌려줍니다.
  오류 코드는 `ERROR_CODES.md` 의 궁금해사전 절을 참고합니다.

### G. 리포트

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/v1/reports/summary` | 최신 완료 세션 리포트 |
| `GET` | `/v1/reports/history?limit=8` | 세션 이력 |

`ReportDashboard.tsx` 의 `Report` 타입과 같은 필드 구성입니다. `sessionTitle`·`misconception`·`learnedLine` 처럼 커리큘럼 본문에 있는 값은 `session_id` 로 프런트가 채웁니다.

**오개념 표시 규칙 (이슈 #6).** 응답의 `synchronized` 는 오답이 하나라도 있으면 true 가 되는
하위 호환 값이므로 오개념 확정 표시에 쓰지 않습니다. 대신 `bottleneck_candidates` 를 씁니다.

```jsonc
{ "...기존 필드...": "...",
  "bottleneck_candidates": [
    { "candidate": "carry_over", "evidence_count": 2, "repeated": true } ] }
```

- `repeated: false`(관찰 1회)는 "이런 모습이 한 번 보였어요" 수준으로만 표현합니다.
- 값은 AI 관찰 집계(`learning_task_outcomes`)에서 나오며, 관찰이 없으면 빈 배열입니다.
- AI 계약상 `concept_result = not_assessed`(도움 요청·입력 오류·장난)는 오답으로 합산하지 않습니다.

### G-2. AI 관찰 이벤트 수신 (내부 전용, 이슈 #6)

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/internal/v1/observations/events` | AI 관찰 이벤트 멱등 수집 |

- 인증: `X-Mormi-Service-Key` 헤더 (`MORMI_OBSERVATION_INGEST_KEY`). 학습자 토큰·CORS 대상이 아닙니다.
- 계약 원본: `Mormi-AI/docs/OBSERVATION_EVENTS.md`. `schema_version` 은 숫자 `1` 입니다.
- 발화사다리(`expression_before/after`) 신규 값은 `L4/L3/L2/L0`, 힌트사다리
  (`hint_before/after`)는 `H0~H3`로 별개 상태값입니다. 과거 `L1` 관찰은 원본을
  보존하되 조회·집계에서 `L2`로 해석하며 단계 번호를 다시 매기지 않습니다.
- 소유권은 이벤트의 `learner_id` 가 아니라 `conversation_id` 로 BE 대화 기록에서 역참조합니다.

```jsonc
// 요청 (전송기가 outbox payload 를 observation 으로 감싼다)
{ "event_id": "evt_...", "schema_version": 1, "event_type": "dialogue_observation",
  "observation": { "observation_id": "observation_...", "conversation_id": "conversation_...",
                   "task_id": "money-count:1", "expression_before": "L4", "expression_after": "L4",
                   "hint_before": "H0", "hint_after": "H0", "concept_result": "correct_partial", "..." : "..." } }
// 200 — 재전송이어도 오류가 아니다
{ "event_id": "evt_...", "status": "processed", "duplicate": false, "observation_id": 1 }
```

| 응답 | 의미 | AI 전송기가 할 일 |
|---|---|---|
| `200` | 반영 완료 (`duplicate: true` 포함) | 없음 |
| `409 unknown_conversation` | 대화 커밋 전에 이벤트가 먼저 도착 | **잠시 후 재전송** |
| `422 unsupported_schema_version` 등 | 내용 자체가 문제 | 재전송 금지. 이벤트는 `failed` 로 보존됨 |
| `401` | 서비스 키 없음/불일치 | 설정 확인. 이벤트는 저장되지 않음 |

### G-3. 별노트 수집·조회 (이슈 #12)

AI가 발행하는 `star_note_created` 이벤트를 위 G-2 와 같은 엔드포인트에서 수집해
BE 가 소유하는 별노트 원장(`star_notes`)에 저장하고, FE 에 목록 API 를 제공합니다.
FE 는 AI 의 별노트 API 를 직접 호출하지 않습니다.

#### 수집 (내부 전용)

`POST /internal/v1/observations/events` — `event_type: "star_note_created"` 로 구분합니다.
인증·수신함 멱등성(`event_id` unique)은 G-2 와 동일하고, 원장에서 `note_id` unique 로
한 번 더 막습니다(다른 event_id 로 재전송된 같은 노트도 한 행만 남음).

```jsonc
// 요청 — 계약 원본: Mormi-AI/docs/OBSERVATION_EVENTS.md "별노트 독립 이벤트(B안)"
{ "event_id": "event_...", "schema_version": 1, "event_type": "star_note_created",
  "star_note": { "note_id": "note_...", "note_version": 1, "learner_id": 17,
                 "conversation_id": "conversation_...", "skill_id": "number-count",
                 "text": "색칠된 칸을 하나씩 세면 모두 3개야.",
                 "attribution": "child", "attribution_label": "아이가 알려줌",
                 "evidence": "direct_explanation",
                 "evidence_links": [{ "observation_id": "observation_...", "source_slot_ids": ["tracking"] }],
                 "active": true, "created_at": "2026-08-19T00:00:00+00:00" } }
// 200 — 재전송이어도 오류가 아니다
{ "event_id": "event_...", "status": "processed", "duplicate": false, "star_note_id": 1 }
```

| 응답 | 의미 | AI 전송기가 할 일 |
|---|---|---|
| `200` | 반영 완료 (`duplicate: true` 포함) | 없음 |
| `409 unknown_conversation` | 대화 커밋 전에 별노트가 먼저 도착 | **잠시 후 재전송** |
| `422 invalid_payload` 등 | 필수값 누락·형식 오류 | 재전송 금지. 이벤트는 `failed` 로 보존됨 |
| `422 star_note_owner_mismatch` | 이벤트의 `learner_id` ≠ 대화 소유자 | 재전송 금지 |
| `401` | 서비스 키 없음/불일치 | 설정 확인. 이벤트는 저장되지 않음 |

- 필수 필드: `note_id`, `note_version`(1 이상), `conversation_id`, `text`, `attribution`, `created_at`.
- 소유권은 G-2 와 같이 `conversation_id` 로 역참조합니다. 이벤트의 `learner_id` 는 검증에만 씁니다.
- **순서 역전 허용**: `evidence_links` 의 관찰이 아직 도착하지 않았어도 수용합니다(FK 없이 ID 만 보존).
  따라서 AI 재시도 표의 `unknown_learner` / `unknown_observation` / `missing_evidence_observation` 은
  **예약만 되어 있고 BE 가 실제로 반환하지 않습니다.**
- 같은 `note_id` 재발행은 `note_version` 이 올랐을 때만 반영합니다. 같은 버전으로 내용만 바꾸면
  조용히 무시되므로, AI 는 수정 시 반드시 버전을 올려야 합니다. `active: false` 재발행 = 목록에서 숨김.
- **과제 집계 연결(이슈 #14)**: 별노트에 `task_id` 가 있으면 같은 세션·과제의
  `learning_task_outcomes.star_note_id / star_note_attribution / star_note_evidence` 에 연결됩니다.
  연결은 원장에서 파생되며, 같은 과제에 노트가 여러 개면 최신(`created_at` 내림차순, 동률이면
  `note_id` 내림차순) 활성 노트 하나를 가리킵니다. 별노트가 집계 행보다 먼저 도착하면 원장에서
  기다렸다가 집계가 만들어질 때 채워지고(순서 역전 허용), `active: false` 재발행이 오면 풀립니다.

#### 조회 (학습자용)

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/v1/learners/{learner_id}/star-notes` | ✓ | 별노트 목록 (본인만). 커서 페이지네이션 |

```jsonc
// GET /v1/learners/1/star-notes?limit=20            — limit 1~50, 기본 20
// GET /v1/learners/1/star-notes?limit=20&cursor=... — cursor 는 직전 응답의 next_cursor
// 200
{
  "star_notes": [
    { "note_id": "note_...", "skill_id": "number-count",
      "text": "색칠된 칸을 하나씩 세면 모두 3개야.",   // AI 원문 그대로. BE 가 재작성하지 않는다
      "attribution": "child", "attribution_label": "아이가 알려줌",
      "evidence": "direct_explanation",
      "scene": "home_teach", "scenario_id": "home_teach", "task_id": "home_teaching",
      "created_at": "2026-08-19T00:00:00Z" }           // AI 가 노트를 만든 시각
  ],
  "next_cursor": "note_..."   // 마지막 페이지면 필드 자체가 없음
}
```

- 정렬: `created_at` 내림차순, 동률이면 `note_id` 내림차순. 커서(keyset) 방식이라
  조회 사이에 새 노트가 생겨도 중복·누락 없이 이어집니다. FE 는 `next_cursor` 를 그대로 넘깁니다.
- 비활성(`active: false`) 노트는 목록에서 제외됩니다.
- 모르는 커서(남의 노트 ID 포함)는 `422 invalid_cursor`. 남의 목록은 `403 forbidden`, 무토큰은 `401`.

#### 배포 순서와 backfill

1. BE 배포 (수신 분기 + 원장 + 목록 API)
2. AI 에 `MORMI_STAR_NOTE_EVENTS_ENABLED=true` 적용 후 재시작
3. AI outbox 의 pending 별노트 적체가 줄어드는지 확인
4. FE 목록 연동 활성화 (Mormi-FE#27)

**backfill 없음**: 플래그를 켜기 전에 AI outbox 에 쌓인 pending 분은 일반 재시도로 유입되지만,
outbox 이전의 역사 별노트는 자동 생성되지 않습니다. 필요해지면 별도 이슈로 진행합니다.
롤백: BE 를 이전 버전으로 되돌리면 별노트 이벤트가 `422 unsupported_event_type` 으로 실패하므로,
**먼저 AI 플래그를 `false` 로 되돌린 뒤** BE 를 롤백합니다(pending 보존, 유실 없음).

### H. 운영

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
- 가입에서 이름 + 참여 번호 + 아이디·비밀번호 → `POST /v1/auth/signup` → 토큰 보관
- 부팅 시 `GET /v1/progress` 로 상태 복구
- 드릴 정답·오답 전건 `POST .../attempts`
- 세션 종료 `POST .../complete` → 지갑·완료목록·해금을 서버 응답으로 갱신
- 카페 전 스테이지 기록 + 방문 복구
- PostHog `identify` 는 `analytics_id` 만 사용

- 실제 집·카페 화면은 Spring BE의 대화 프록시가 돌려준 전체 `TurnContract`를 렌더링합니다.
- 개발용 `/api/mormi/*` 직접 BFF는 `/ai-test` 전용이며 운영 학습자 화면에서 사용하지 않습니다.
- FE는 BE 시도 저장과 AI 턴이 모두 성공하기 전에 다음 단계를 성공으로 표시하지 않습니다.

---

## 4. DB 스키마 (인증·학습 핵심 테이블, `V15` 기준)

```
accounts             id, login_id UNIQUE, password_hash, role(learner|educator), created_at
auth_tokens          id, account_id, token_hash UNIQUE, expires_at, revoked_at, created_at
learners             id, account_id UNIQUE, display_name, research_code UNIQUE,
                     analytics_id UUID UNIQUE, conversation_storage_consent,
                     retention_policy, onboarding_completed_at, created_at
organizations        id, name, created_at
educators            id, organization_id, account_id UNIQUE, display_name, role(직위), created_at
cohorts              id, organization_id, name, class_code UNIQUE, created_at
cohort_research_codes id, cohort_id, code UNIQUE, issued_by(educator), created_at
learner_enrollments  id, learner_id, cohort_id, enrolled_at, left_at
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

- 로그인 세션은 `auth_tokens` 에 행 단위로 쌓입니다. 계정당 여러 행이 살아 있을 수 있어 다기기 로그인이 되고, 로그아웃은 행을 지우지 않고 `revoked_at` 을 남깁니다.
- 계정은 `accounts` 하나로 전역 유니크입니다. 학생·교사 프로필이 `account_id` 로 계정을 가리키며, 역할별 토큰 테이블은 없습니다.
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
| 3 | 기존 `attempts.support_level` ↔ 발화사다리 매핑 | 직접 변환하지 않음. 신규 관찰은 `L4/L3/L2/L0`, 과거 `L1`은 원본 보존 후 조회·집계에서 `L2`로만 합산. 재번호화하지 않음 |
| 4 | 별노트 저장 주체 | AI 가 생성·발행하고 **Spring 이 서비스 원장(`star_notes`)을 소유** (이슈 #12, G-3 참조). FE 는 Spring 목록 API 만 사용 |
| 4-1 | 별노트 → BE 이벤트 계약 | **확정.** `star_note_created` 이벤트로 수집 (G-3). `learning_task_outcomes.star_note_*` 컬럼은 원장에서 파생해 연결됨 (이슈 #14, G-3 "과제 집계 연결" 참조) |
| 5 | `conversation_storage_consent` 관리 주체 | Spring `learners` 및 동의 변경 API. AI가 실제 암호화·삭제 수행 |
| 6 | 참여 번호 발급 방식 | **확정.** 교사가 학급 화면(`POST /v1/cohorts/{id}/research-codes`)에서 사전 발급해 전달. 아이 가입 시 자동 재적 (이슈 #17, B-2 참조) |
