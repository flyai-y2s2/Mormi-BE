# Mormi 백엔드 구현 API 목록

프로토타입(Mormi-FE)으로 실제 대상자 테스트를 하기 위해 필요한 API 전체 목록입니다.

## 0. 현재 상태

| 레포 | 역할 | 상태 |
|---|---|---|
| `Mormi-FE` | Next.js 16 화면 + BFF | 화면 완성. **모든 데이터가 localStorage**, 백엔드 호출 0건 |
| `Mormi-AI` | FastAPI + LangGraph 대화 | **완성**. 8개 엔드포인트 구현됨 |
| `Mormi-BE` | Spring Boot 학습 기록 | **`GET /health` 뿐**. 엔티티·마이그레이션 0개 |

즉 이번에 만들 것은 **Spring 학습 API 18개 + Next.js BFF 4개**, 그리고 FE의 localStorage를 이 API로 교체하는 작업입니다.

### FE가 지금 localStorage에 들고 있는 것 (전부 서버로 옮겨야 함)

| 키 | 내용 | 위치 |
|---|---|---|
| `mormey-learner` | `{id:1, name}` — **모든 아이가 id=1 하드코딩** | `MoramiApp.tsx:891` |
| `morami-completed-sessions` | 완료 세션 id 배열 → 카페 해금 판정 | `MoramiApp.tsx:1381` |
| `mormey-coins` | 지갑 잔액(기본 6000) | `MoramiApp.tsx:1395` |
| `morami-onboarding-complete` | `"true"` | `MoramiApp.tsx:1450` |
| `morami-report` / `-history` | 세션 리포트 | `MoramiApp.tsx:1179` |

카페(`CafeJourney.tsx`)는 **저장이 아예 없습니다.** `journeyProgress` 0→4가 순수 React state라 새로고침하면 1번 스테이지로 돌아갑니다. 결제·거스름돈 기록도 PostHog 이벤트로만 나가고 어디에도 안 남습니다.

---

## 1. Mormi-AI — 이미 구현 완료 (만들 필요 없음)

인증은 `X-Mormi-Service-Key` 헤더. 브라우저가 직접 호출하지 않고 Next.js BFF를 경유합니다.

| Method | Path | 용도 |
|---|---|---|
| `GET` | `/health` | 상태 확인 |
| `POST` | `/v1/practice-results` | 반복학습 결과 적재 → `practice_result_id` 반환 |
| `POST` | `/v1/conversations` | 대화 시작 → `TurnContract` |
| `POST` | `/v1/conversations/{conversation_id}/responses` | 아이 응답 제출 → 다음 `TurnContract` |
| `GET` | `/v1/conversations/{conversation_id}` | 최신 턴 복구 (409 이후) |
| `GET` | `/v1/learners/{learner_id}/skill-profiles` | 스킬 프로파일 |
| `GET` | `/v1/learners/{learner_id}/star-notes` | 별노트 목록 |
| `GET` | `/v1/conversations/{conversation_id}/transcript` | 대화 원문(암호화 저장분) |

`response_id` 멱등, 409 conflict + `state_version`, 503 시 상태 불변까지 이미 구현돼 있습니다.

---

## 2. Mormi-BE (Spring Boot) — 구현 대상

> 규칙: 보상 계산, 정오 판정, 해금 판정은 **전부 서버가 확정**합니다. FE는 표시만 합니다.
> 현재 FE는 이 셋을 모두 클라이언트에서 계산해 localStorage에 씁니다.

### A. 학습자

| # | Method | Path | 설명 |
|---|---|---|---|
| 1 | `POST` | `/v1/learners` | 온보딩 이름 제출. 학습자 생성 |
| 2 | `GET` | `/v1/learners/{learner_id}` | 프로필 조회 |
| 3 | `POST` | `/v1/learners/auth` | 연구코드로 기존 학습자 복구 → 토큰 재발급 |

**1) `POST /v1/learners`**
```jsonc
// req
{ "display_name": "민준", "research_code": "MORMI-A03" }
// res 201
{
  "id": 1,
  "display_name": "민준",
  "research_code": "MORMI-A03",
  "analytics_id": "b3f1...",      // PostHog identify 전용 가명 ID
  "access_token": "...",           // 이후 요청 Authorization: Bearer
  "created_at": "2026-08-12T10:00:00+09:00"
}
```
- FE의 `name` ← 응답의 `display_name` 매핑.
- `display_name`은 화면 표시 전용. **PostHog·LLM 프롬프트에 절대 안 보냄** (현재 `MoramiApp.tsx:1109`가 `learnerName`을 LLM에 보내고 있어 수정 필요).
- `analytics_id`는 `learners.id`와 별개여야 함 (`docs/posthog-plan.md` 요구사항).

### B. 진행도 / 해금

| # | Method | Path | 설명 |
|---|---|---|---|
| 4 | `GET` | `/v1/progress` | 앱 시작 시 1회. 전체 상태 복구 |
| 5 | `GET` | `/v1/themes` | 장소별 해금 상태 |

**4) `GET /v1/progress`** — localStorage 4개 키를 한 번에 대체
```jsonc
{
  "learner": { "id": 1, "display_name": "민준" },
  "onboarding_complete": true,
  "completed_session_ids": ["money-count", "money-price"],
  "wallet_balance": 7850,
  "level": 1,                    // floor(완료수/4)+1  — MoramiApp.tsx:991
  "stars": 6,                    // 완료수*3
  "cafe_unlocked": false,
  "active_learning_session_id": null,   // 진행 중 세션 복구용
  "active_cafe_visit_id": null
}
```
카페 해금 = `money-count`, `money-price`, `money-budget`, `money-mission` 4개 전부 완료 (`journey-config.ts:1-6`). **서버가 계산하고 FE는 표시만.**

### C. 학습 세션 (집)

| # | Method | Path | 설명 |
|---|---|---|---|
| 6 | `POST` | `/v1/learning-sessions` | 세션 시작 |
| 7 | `GET` | `/v1/learning-sessions/{id}` | 새로고침 복구 |
| 8 | `POST` | `/v1/learning-sessions/{id}/attempts` | 문제 시도 1건 기록 |
| 9 | `POST` | `/v1/learning-sessions/{id}/complete` | 종료 + 보상 정산 |

**6) `POST /v1/learning-sessions`**
```jsonc
// req
{ "learner_id": 1, "curriculum_session_id": "money-count", "variant_seed": 1284 }
// res 201
{ "learning_session_id": "session_a1b2", "started_at": "...", "expires_in_seconds": 480 }
```
`variant_seed`를 꼭 받으세요. FE는 문제를 `varyProblem()`으로 런타임 생성하므로(`MoramiApp.tsx:431-592`), seed 없이는 **아이가 실제로 뭘 틀렸는지 재구성이 불가능합니다.**

**8) `POST /v1/learning-sessions/{id}/attempts`**
```jsonc
// req
{
  "activity": "drill",           // drill | teach | transfer
  "attempt_no": 3,
  "item_id": "money-count:2",    // drill_index 포함
  "is_correct": false,
  "elapsed_ms": 4200,
  "answer_meta": {               // JSONB. 구조 데이터만
    "selected_choice_id": "c2",
    "wrong_count_before": 1,
    "misconception_tag": "coin_count_not_value"
  }
}
// res 200
{ "attempt_id": 91, "reward_granted": 0, "session_reward_subtotal": 350 }
```
- 멱등키 `(learning_session_id, activity, attempt_no)`.
- **오답 상세가 현재 어디에도 저장되지 않습니다.** `wrongDrillAnswers`는 정답 맞히면 초기화되고(`MoramiApp.tsx:1211`) 개수만 PostHog로 나갑니다. 이번에 새로 수집해야 하는 데이터입니다.
- `answer_meta`에 아이 이름·자유발화 원문·음성은 넣지 않습니다.

**9) `POST /v1/learning-sessions/{id}/complete`** — 한 트랜잭션으로 보상 확정
```jsonc
// req
{
  "conversation_id": "conversation_x9",   // Mormi-AI 대화 ID (가르치기 500원 검증용)
  "transfer_solved": true,
  "timed_out": false,
  "elapsed_seconds": 142,
  "idempotency_key": "complete:session_a1b2"
}
// res 200
{
  "drill_reward": 850, "teach_reward": 500, "total_reward": 1350,
  "wallet_balance": 7350,
  "completed_session_ids": [...], "cafe_unlocked": true,
  "practice_result_id": "practice_77"
}
```

보상 규칙 (서버 소유, `MoramiApp.tsx:1194-1200` / `mormey.md` §4):

| 정답 전 오답 수 | 보상 |
|---:|---:|
| 0개 | 200원 |
| 1개 | 150원 |
| 2개 | 100원 |
| 3개 | 50원 |

5문제 × 최대 200원 = 드릴 최대 **1,000원**, 가르치기 성공 고정 **500원**. 지갑 시작값 6,000원.

500원은 `conversation_id`로 Mormi-AI에 `completion.teach_reward_eligible == true`를 **검증한 뒤에만** 지급합니다. `bright_exit`는 세션은 끝나되 500원 없음. 멱등키 `teach-reward:{learning_session_id}:{conversation_id}`.

> ⚠️ FE의 `saveReport()`는 성공 경로에서 **두 번 호출**됩니다 (`MoramiApp.tsx:1367`, `:1379`). 멱등 처리 없으면 보상이 두 번 들어갑니다.

### D. 카페

| # | Method | Path | 설명 |
|---|---|---|---|
| 10 | `POST` | `/v1/cafe-visits` | 방문 시작 (해금 검증) |
| 11 | `GET` | `/v1/cafe-visits/{id}` | 스테이지 진행 복구 |
| 12 | `POST` | `/v1/cafe-visits/{id}/queue` | 줄 서기 제출 |
| 13 | `POST` | `/v1/cafe-visits/{id}/menu` | 메뉴 2개 선택 |
| 14 | `POST` | `/v1/cafe-visits/{id}/payments` | 결제 제출 |
| 15 | `POST` | `/v1/cafe-visits/{id}/change` | 거스름돈 제출 |
| 16 | `POST` | `/v1/cafe-visits/{id}/complete` | 방문 완료 |

`stage = queue | menu | calculate | change | complete`. **다음 돌다리 잠금 해제는 서버가 판정합니다.**

**14) `POST /v1/cafe-visits/{id}/payments`**
```jsonc
// req
{ "target_amount": 10000, "order_total": 7000,
  "counts": { "100": 0, "500": 0, "1000": 5, "5000": 1 },
  "attempt_no": 2 }
// res 200
{ "is_correct": true, "paid_amount": 10000, "difference": 0,
  "stage": "change", "next_stage_unlocked": true, "attempts": 2 }
```
버튼 클릭 원본 로그는 저장하지 않고 **최종 화폐별 개수만** 저장합니다.

메뉴는 6종 고정(아메리카노 3000 / 우유 2000 / 딸기주스 4000 / 쿠키 2000 / 딸기케이크 3000 / 샌드위치 4000), 정확히 2개, 합계 10,000원 이하. 결제는 10,000원 정확히 일치해야 통과. 거스름돈은 `10000 - order_total`을 1000·500원으로만 구성.

> 참고: `cafe_visits` 테이블을 contract대로 `menu_id` 단수로 만들면 안 됩니다. **메뉴 2개**이고 결제/거스름돈이 별개 단계라 `cafe_visit_stages` 자식 테이블이 필요합니다.

### E. 리포트

| # | Method | Path | 설명 |
|---|---|---|---|
| 17 | `GET` | `/v1/reports/summary` | 최신 세션 리포트 |
| 18 | `GET` | `/v1/reports/history` | 세션 이력 (FE는 8건 보관하나 현재 미사용) |

`ReportDashboard.tsx:10-30`의 `Report` 타입이 그대로 응답 스키마입니다:
`date, sessionId, sessionTitle, sessionUnit, sessionLevel, masteryTarget, repetitions, masterySeconds, misconception, synchronized, transfer, ladder, timedOut, learnedLine, learnerName, learnerId, earnedCoins, drillCoins, teachCoins`

`ladder`가 현재 FE는 0~3인데 Mormi-AI 계약은 `L4~L0`입니다. **매핑 규칙을 확정해야 합니다.**

---

## 3. Mormi-FE — Next.js BFF 라우트

서비스 키(`MORMI_DIALOGUE_SERVICE_KEY`)는 서버에만 두고 브라우저 번들에 넣지 않습니다.

| # | Method | Path | → 전달 |
|---|---|---|---|
| 19 | `POST` | `/api/dialogue/conversations` | Mormi-AI `POST /v1/conversations` |
| 20 | `POST` | `/api/dialogue/conversations/{id}/responses` | Mormi-AI `.../responses` |
| 21 | `GET` | `/api/dialogue/conversations/{id}` | Mormi-AI `GET /v1/conversations/{id}` |
| 22 | `POST` | `/api/mormi/respond` | 기존 `/api/morami/respond`의 신규 경로. 호환 기간 동안 같은 핸들러 |

---

## 4. DB 스키마 (Flyway `V1__init.sql`)

`ddl-auto: validate` + `db/migration` 비어 있음 → **엔티티 추가하는 순간 기동 실패합니다.** 마이그레이션이 먼저입니다.

```
learners             id, display_name, research_code UNIQUE, analytics_id UUID,
                     conversation_storage_consent, retention_policy, created_at
learning_sessions    id, learner_id, curriculum_session_id, variant_seed,
                     started_at, completed_at, scaffold_level, timed_out,
                     conversation_id, practice_result_id
attempts             id, learning_session_id, activity, attempt_no, item_id,
                     is_correct, elapsed_ms, answer_meta JSONB
                     UNIQUE(learning_session_id, activity, attempt_no)
theme_progress       learner_id, theme_id, unlocked_at, completed_at
cafe_visits          id, learner_id, stage, order_total, target_amount,
                     started_at, completed_at
cafe_visit_stages    id, cafe_visit_id, stage, attempt_no, is_correct,
                     payload JSONB   -- 화폐별 개수, 선택 메뉴 id 등
reward_ledger        id, learner_id, learning_session_id, source, amount,
                     idempotency_key UNIQUE, created_at
```

- 모든 아동 데이터 테이블에 `learner_id` 인덱스.
- 지갑 잔액은 별도 컬럼 없이 `reward_ledger` 합계로 도출.
- 대화 원문은 이 DB에 넣지 않습니다 (Mormi-AI가 암호화 저장).

---

## 5. 구현 순서

1. **Flyway `V1__init.sql` + `SecurityConfig` + CORS** — 지금 Security가 클래스패스에 있는데 `SecurityFilterChain` 빈이 없어서 `/health` 포함 전 엔드포인트가 랜덤 비밀번호 Basic 인증에 막힙니다. CORS 설정도 없어 FE가 아예 호출 못 합니다.
2. 학습자 + 진행도 (1·2·4) → FE 온보딩·부팅 연결
3. 학습 세션 + 시도 + 완료 (6·8·9) → 보상을 서버로 이전
4. 카페 (10~16) → 지금 완전히 유실되는 데이터 확보
5. 리포트 (17)
6. BFF (19~22) → Mormi-AI 연결

1~3만 되어도 대상자 테스트에서 "누가 무엇을 얼마나 했는가"는 남습니다. 일정이 촉박하면 여기까지가 최소선입니다.

---

## 6. 확정 필요한 사항

| # | 항목 | 권장 |
|---|---|---|
| 1 | 학습자 식별 방식 | 연구코드 + Bearer 토큰. 지금은 모든 아이가 `id=1`이라 데이터가 섞임 |
| 2 | RDS 안에서 Spring / Mormi-AI 데이터 분리 | 같은 인스턴스, 스키마 분리. Flyway가 대화 테이블을 validate하지 않도록 `search_path` 격리 |
| 3 | 지갑 vs 카페 10,000원 | 현재 완전 분리(지갑 6000, 카페 하드코딩 10000). 분리 유지 권장 — 통합하면 잔액 부족 시 카페 진행 불가 처리가 추가로 필요 |
| 4 | `ladder` 0~3 ↔ `L4~L0` 매핑 | 리포트와 대화 계약이 다른 척도를 씀 |
| 5 | 별노트 저장 주체 | Mormi-AI가 이미 `star-notes` 보유. Spring은 조회만 |
| 6 | `conversation_storage_consent` 관리 주체 | `learners`에 보관하고 대화 시작 시 전달 |
