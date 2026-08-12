# 모르미 프론트엔드–대화 백엔드 연동 협의안

> 프론트엔드 팀에 전달하기 위한 문서입니다.  
> 목적은 기존 구현을 평가하거나 폐기하는 것이 아니라, 최종 모르미 설계에 맞춰 프론트와 AI 대화 백엔드의 책임을 명확히 나누는 것입니다.

## 1. 먼저 공유드리고 싶은 결론

현재 `backend-contract.md`에 정의된 학습자·진행도·시도 기록 구조는 공통 기반으로 활용하면 좋겠습니다.

다만 모르미 가르치기와 카페 대화는 단순한 정오 기록보다 훨씬 많은 상태를 다뤄야 하므로, **일반 학습 기록 API와 AI 대화 API를 분리해서 설계**하려고 합니다.

- 일반 학습 기록 API: 반복 문제 결과, 진행도, 장소 해금, 카페 결제 기록
- AI 대화 API: 아이 발화 이해, 발화사다리, 힌트사다리, 모르미 대사, 도움 카드, 별노트

프론트는 대화 결과를 추론하지 않고, 백엔드가 반환한 **턴 계약(Turn Contract)**을 화면에 표시하는 구조를 제안합니다.

---

## 2. 현재 프론트 코드에서 확인한 상태

현재 구현은 프로토타입으로서 자연스러운 상태이며, FastAPI 연동 전 다음 로직이 프론트에 임시로 들어가 있습니다.

- 진행도와 리포트가 `localStorage`에 저장됨
- 모르미 대화는 Next.js의 `/api/morami/respond`가 담당함
- 하나의 LLM 호출이 아이 발화 판정과 모르미 대사 생성을 함께 수행함
- 발화사다리 하강과 성공 여부를 프론트 코드가 결정함
- 별노트 문장은 커리큘럼의 정적 문장을 사용함
- 카페 대화와 정오 피드백이 `CafeJourney.tsx`에 하드코딩되어 있음
- 현재 발화사다리는 숫자 `3~0` 네 단계로 구현되어 있음
- `NEXT_PUBLIC_API_BASE_URL`은 선언되어 있지만 아직 실제 호출에는 사용되지 않음

최종 버전에서는 이 중 **교육적 판단에 해당하는 부분만 백엔드로 이동**하고, 현재 UI와 시각 자료는 최대한 유지할 수 있습니다.

---

## 3. 역할 분담 제안

| 영역 | 프론트엔드 | 대화 백엔드 |
|---|---|---|
| 화면·캐릭터·애니메이션 | 렌더링 | 표시할 표정·연출 의미 전달 |
| 반복 문제 | 문제 표시, 탭 입력, 즉시 반응 | 반복 결과 요약 수신 |
| 아이 입력 | 텍스트·선택·세기·세로식 입력 수집 | 입력 의미 분석 |
| 정오·부분 답변 판정 | 다시 판정하지 않음 | 분류기와 검증 코드가 결정 |
| 발화사다리 | 받은 입력 UI를 렌더링 | L4~L0 전환 결정 |
| 힌트사다리 | 도움 카드를 자동으로 표시 | H0~H3 전환과 내용 결정 |
| 모르미 대사 | 말풍선에 표시 | 안전한 대사 생성·검증 |
| 별노트 | 전달받은 문장과 귀속 표시 | 문장·귀속 생성 결정 |
| 네트워크 재시도 | 입력을 보존하고 같은 ID로 재전송 | 중복 요청에 같은 결과 반환 |
| 진행도·해금 | 서버 결과 표시 | 서버에서 최종 상태 계산 |

핵심 원칙은 다음과 같습니다.

> 프론트는 아이의 답을 보고 “맞다/틀리다/사다리를 내린다/별노트에 적는다”를 추론하지 않습니다. 백엔드가 다음 화면에 필요한 상태를 하나의 턴 계약으로 반환합니다.

---

## 4. 최종 발화사다리와 힌트사다리

### 발화사다리: 표현을 돕는 축

| 단계 | 아이에게 요구하는 방식 |
|---|---|
| `L4` | 자기 말로 자유롭게 설명 |
| `L3` | 짧은 핵심 답과 이유 |
| `L2` | 선택지를 이용해 설명 |
| `L1` | 사고 과정을 작은 단계로 나누어 완성 |
| `L0` | 도움 카드와 함께 수행·모델링 |

현재 프론트의 `3~0`과 다른 체계이므로, API와 프론트 모두 숫자가 아니라 `L4`, `L3` 같은 문자열 enum을 사용하는 것을 권장합니다.

### 힌트사다리: 생각을 돕는 축

| 단계 | 도움 수준 |
|---|---|
| `H0` | 힌트 없음 |
| `H1` | 짧은 언어 단서 |
| `H2` | 시각·표상 도움 |
| `H3` | 도움 카드로 함께 수행·모델링 |

발화 단계와 힌트 단계는 반드시 별도 상태로 다룹니다.

```json
{
  "expression_level": "L3",
  "hint_level": "H0"
}
```

아이가 개념은 알지만 문장 표현이 어려우면 `L`만 내려가고, 선택형으로도 개념에서 막히면 `H`가 올라갑니다.

---

## 5. 모르미 대화에서 백엔드가 판정하는 것

단순한 `is_correct` 대신 다음과 같은 응답 유형을 구분합니다.

- 완전한 가르침
- 일부만 맞게 말한 부분 답변
- 정답은 말했지만 이유가 빠진 답변
- 이유는 말했지만 결론이 빠진 답변
- 표현이 어려워 막힌 상태
- 개념에서 막힌 상태
- 개념적 오답 또는 오개념
- 잘 모르겠다는 표현
- 입력·음성 인식 오류
- 장난이나 주제 이탈
- 개인정보·성적 발언·프롬프트 해킹 등 안전 대응 대상

백엔드는 아이가 이미 말한 사실을 `verified_slots`로 기억합니다. 예를 들어 아이가 “왼쪽”이라고 먼저 말하고 다음 턴에 이유만 설명하더라도, 앞의 답을 잊지 않고 합쳐서 처리합니다.

이 구조는 줄 서기뿐 아니라 메뉴값 덧셈, 거스름돈, 집에서 모르미 가르치기에도 동일하게 적용합니다.

---

## 6. API 구분 제안

팀원이 작성한 일반 API는 다음 목적에 유지할 수 있습니다.

```text
POST /v1/learners/anonymous
GET  /v1/progress
POST /v1/learning-sessions
POST /v1/learning-sessions/{id}/attempts
POST /v1/learning-sessions/{id}/complete
GET  /v1/themes
POST /v1/cafe-visits
POST /v1/cafe-visits/{id}/payments
POST /v1/cafe-visits/{id}/complete
```

AI 대화는 이름이 충돌하지 않도록 별도 경로를 사용하려고 합니다.

```text
POST /v1/practice-results
POST /v1/conversations
POST /v1/conversations/{id}/responses
GET  /v1/conversations/{id}
GET  /v1/learners/{id}/skill-profiles
GET  /v1/learners/{id}/star-notes
```

교사용 리포트 API는 이번 대화 백엔드 작업 범위에서는 제외합니다.

---

## 7. 대화 시작 요청

집에서 반복학습이 끝난 뒤 모르미 가르치기를 시작하는 예시입니다.

```http
POST /v1/conversations
Content-Type: application/json
```

```json
{
  "learner_id": 1,
  "scene": "home_teach",
  "scenario_id": "home_addition_teach",
  "practice_result_id": "practice_123",
  "practice_summary": {
    "skill_id": "basic_addition",
    "attempts": [
      {
        "item_id": "add_01",
        "correct": true,
        "latency_ms": 3400
      },
      {
        "item_id": "add_02",
        "correct": false,
        "misconception_tag": "count_all_error",
        "latency_ms": 6100
      }
    ]
  }
}
```

카페는 다음처럼 시작합니다.

```json
{
  "learner_id": 1,
  "scene": "cafe",
  "scenario_id": "cafe_outing"
}
```

반복학습 원문 문제 전체를 넘길 필요는 없고, 기술 ID·정오·시도 횟수·오개념 태그·반응 시간처럼 시작 발화 단계를 결정하는 정보만 전달하면 됩니다.

---

## 8. 아이 응답 요청

프론트는 최신 `turn_id`를 돌려보내고, 사용자 행동마다 `response_id` UUID를 한 번 생성합니다.

### 자유 발화

```http
POST /v1/conversations/{conversation_id}/responses
Content-Type: application/json
```

```json
{
  "turn_id": "turn_123",
  "response_id": "9cda3c1e-6539-4b35-9ac5-c63f91e203b1",
  "type": "text",
  "text": "왼쪽이 더 빨라. 세 명이 다섯 명보다 적으니까.",
  "latency_ms": 5200
}
```

### 선택지

```json
{
  "turn_id": "turn_124",
  "response_id": "response_457",
  "type": "choice",
  "choice_ids": ["left_queue"],
  "latency_ms": 1800
}
```

### 사람 세기나 세로식

```json
{
  "turn_id": "turn_125",
  "response_id": "response_458",
  "type": "count",
  "values": {
    "left_count": 3,
    "right_count": 5
  },
  "latency_ms": 7400
}
```

```json
{
  "turn_id": "turn_126",
  "response_id": "response_459",
  "type": "equation",
  "values": {
    "ones": 0,
    "tens": 0,
    "hundreds": 11,
    "thousands": 7,
    "result": 8100
  },
  "latency_ms": 12300
}
```

화면에 보인 선택지 문구를 다시 보내기보다 `choice_id`를 보내는 것이 안전합니다. 백엔드는 해당 턴에서 실제로 허용한 선택지인지 검증합니다.

---

## 9. 턴 계약 응답

프론트는 아래 응답만 보고 다음 화면을 렌더링합니다.

```json
{
  "session_id": "conversation_123",
  "turn": {
    "turn_id": "turn_127",
    "scene": "cafe",
    "scenario_id": "cafe_outing",
    "task_id": "queue_compare",
    "stage_id": "queue",
    "task_index": 0,
    "mormi": {
      "text": "아, 왼쪽이구나. 그런데 왜 왼쪽이 더 빠른 거야?",
      "mood": "curious",
      "max_lines": 2
    },
    "input": {
      "kind": "text",
      "placeholder": "모르미에게 네 생각을 알려줘",
      "choices": [],
      "target_slots": ["reason"]
    },
    "visual": {
      "type": "queue_scene",
      "data": {
        "left_count": 3,
        "right_count": 5,
        "counting_enabled": false
      }
    },
    "help_card": null,
    "note_update": null,
    "status": "active",
    "state_version": 4,
    "pedagogy": {
      "expression_level": "L3",
      "hint_level": "H0",
      "subgoal_id": "explain_reason",
      "verified_slots": {
        "chosen_queue": "left"
      },
      "bottleneck": "missing_reason"
    }
  }
}
```

`pedagogy`는 개발·QA 단계에서만 노출하고, 운영 아동 화면에서는 사용하지 않거나 숨겨도 됩니다.

---

## 10. 입력 UI 렌더링 규칙

프론트는 `turn.input.kind`에 따라 하나의 입력 UI만 표시합니다.

| `input.kind` | 표시할 UI | 보낼 `type` |
|---|---|---|
| `text` | 말하기·텍스트 입력 | `text` |
| `choices` | 일반 선택지 | `choice` |
| `fill` | 빈칸 완성 선택지 | `fill` |
| `count` | 사람·물건 직접 세기 | `count` |
| `equation` | 세로식 입력 | `equation` |
| `joint` | 도움 카드와 함께 수행 | `action` |
| `button` | 다음·확인 등 진행 버튼 | `action` |
| `none` | 입력 없음, 완료 연출 | 전송하지 않음 |

선택지의 정답 여부나 다음 질문은 프론트에서 판단하지 않습니다.

---

## 11. 도움 카드

궁금해사전 대신 **도움 카드**를 사용합니다.

- 현재 MVP에서는 아이가 아이콘을 눌러 임의로 여는 기능보다, 힌트사다리에 따라 백엔드가 자동 공개하는 기능을 우선합니다.
- `help_card`가 `null`이 아니고 `auto_open=true`이면 화면에서 자동으로 엽니다.
- 도움말의 주체는 모르미가 아니라 시스템의 도움 카드입니다.
- 모르미 말풍선 안에 정답 힌트를 섞지 않습니다.

```json
{
  "help_card": {
    "visible": true,
    "auto_open": true,
    "level": "H2",
    "title": "도움 카드",
    "body": "왼쪽은 3명, 오른쪽은 5명이야. 두 수를 나란히 비교해보자.",
    "visual_type": "number_cards",
    "visual_data": {
      "numbers": [3, 5]
    }
  }
}
```

---

## 12. 별노트

현재 프론트의 정적 `simpleLearnedLine()`을 별노트에 그대로 넣는 방식은 최종 버전에서 사용하지 않습니다.

백엔드가 `note_update`를 반환한 경우에만 별노트에 추가합니다.

```json
{
  "note_update": {
    "note_id": "note_123",
    "skill_id": "compare_quantity",
    "text": "사람이 더 적은 줄에 서면 덜 기다려.",
    "attribution": "child",
    "evidence": "direct_explanation",
    "attribution_label": "지우가 알려줌"
  }
}
```

- 아이가 자기 말로 독립적으로 일반화함: `child` / “○○가 알려줌”
- 선택지·빈칸·도움 카드로 함께 완성함: `coauthored` / “○○와 같이 공부함”
- 이름만 말한 내용, 장난, 질문, 사실과 다른 설명은 별노트에 들어가지 않음
- 아이가 아직 말하지 않은 결론을 모르미가 채워 넣어 `child`로 기록하지 않음

---

## 13. 원문 발화 저장과 개인정보

기존 `backend-contract.md`에는 자유 입력 원문을 저장하지 않는다고 적혀 있지만, 대화 품질 검증과 문맥 유지를 위해 다음 항목은 저장이 필요합니다.

- 모르미가 실제로 한 질문
- 아이의 원문 텍스트 발화
- 아이가 누른 선택지 ID
- 발화 분류 결과와 검증된 사실 슬롯

다만 일반 `attempts.answer_meta`에는 원문을 넣지 않습니다. 대화 원문은 별도 보호 테이블에 암호화해서 저장하고 접근 권한과 보존 기간을 따로 둡니다.

- 음성 파일은 저장하지 않음
- PostHog에는 원문·이름·별노트 문장을 보내지 않음
- 브라우저 `localStorage`에 대화 전체를 장기 저장하지 않음
- 운영 환경에서는 기관·보호자 동의 정책에 따라 원문 저장 여부를 제어

따라서 일반 학습 기록의 개인정보 최소화 원칙은 유지하면서, AI 대화 데이터만 별도 정책으로 관리합니다.

---

## 14. 인증과 비밀키

브라우저 코드에 `X-Mormi-Service-Key` 같은 서버 비밀키를 넣으면 안 됩니다. `NEXT_PUBLIC_` 환경 변수로도 노출하면 안 됩니다.

해커톤 MVP에서는 다음 구조를 권장합니다.

```text
브라우저
  → Next.js 서버 API/BFF
  → FastAPI 대화 백엔드
```

현재 `/api/morami/respond` 라우트를 FastAPI 프록시 역할로 바꾸면 기존 프론트 구조를 비교적 적게 수정할 수 있습니다. 서비스 키는 Next.js 서버 환경 변수에만 두고, 브라우저에는 전달하지 않습니다.

추후에는 `/v1/learners/anonymous`가 학습자 범위 토큰을 발급하고 브라우저가 FastAPI를 직접 호출하는 구조로 전환할 수 있습니다.

---

## 15. 중복 클릭·재시도·오류 처리

### 중복 클릭

- 버튼을 누르는 즉시 해당 입력 영역을 잠금
- 한 사용자 행동마다 `response_id` UUID를 한 번만 생성
- 네트워크 재시도에는 새 ID를 만들지 않고 같은 ID 사용
- 백엔드는 같은 `response_id`에 기존 턴 응답을 그대로 반환

### 오류별 처리

| 상황 | 프론트 처리 |
|---|---|
| 네트워크 오류 | 아이 입력과 마지막 성공 턴을 그대로 유지하고 재시도 표시 |
| `409` 오래된 턴 | 세션 GET으로 최신 턴 복구 후 화면 갱신 |
| `422` 잘못된 요청 | 입력 형식 오류로 처리하고 개발 로그 확인 |
| `503` 발화 이해 실패 | 성공·실패로 임의 처리하지 않고 같은 `response_id`로 재시도 |

API 실패 시 프론트의 fallback 문구만 보여주면서 세션을 성공 처리하면 안 됩니다. 화면 상태도 성공 턴을 받은 뒤에만 변경합니다.

일반 진행도 이벤트는 오프라인 큐에 쌓을 수 있지만, **대화 응답은 다음 질문을 받아야 진행할 수 있으므로 나중에 일괄 동기화하는 방식이 적합하지 않습니다.**

---

## 16. 캐릭터 표정 매핑

백엔드는 이미지 파일명을 직접 지시하지 않고 의미 단위의 `mood`를 반환합니다. 프론트는 현재 보유한 이미지에 매핑합니다.

```ts
const mormiMoodImage = {
  curious: "/morami/confused-cutout.png",
  listening: "/morami/calm-cutout.png",
  thinking: "/morami/calm-cutout.png",
  relieved: "/morami/happy-cutout.png",
  celebrating: "/morami/celebrate-cutout.png",
} as const;
```

이렇게 하면 추후 캐릭터 이미지가 바뀌더라도 백엔드 계약을 변경하지 않아도 됩니다.

모르미 대사는 백엔드에서 다음 조건을 검증합니다.

- 최대 50자
- 최대 두 줄
- 아이를 맞다·틀리다고 평가하지 않음
- 모르미가 정답을 이미 아는 듯한 표현 금지
- 도움 카드 내용을 모르미가 직접 가르치는 말투 금지
- “연습”, “문제”, “미션”처럼 실제 생활 몰입을 깨는 메타 표현 금지

---

## 17. 프론트 수정 체크리스트

### 우선 연결에 필요한 작업

- [ ] `childName = "지우"` 하드코딩 제거 또는 표시용 프로필 값으로 교체
- [ ] `/api/morami/respond`를 FastAPI 대화 API 프록시로 변경
- [ ] `teachResponseMatches()`의 최종 성공 판정 제거
- [ ] `lowerLadder()`의 교육적 전환 판단 제거
- [ ] `answerLadder()`의 정오·다음 단계 판단 제거
- [ ] 숫자 `3~0` 발화사다리를 `L4~L0` 문자열 상태로 교체
- [ ] `H0~H3` 도움 카드 상태 추가
- [ ] `turn.input.kind` 기반 입력 컴포넌트 전환
- [ ] `turn.visual` 기반 카페·집 시각 자료 렌더링
- [ ] `note_update`가 있을 때만 별노트 추가
- [ ] `status=completed`일 때만 완료 화면 이동
- [ ] 진행 중 버튼 잠금과 `response_id` 멱등 재시도 적용

### 프론트에 유지해도 되는 작업

- [ ] 반복 문제의 선택 UI와 즉각적인 시각 효과
- [ ] 사람을 눌러 세는 조작 UI
- [ ] 세로식 숫자 입력 UI
- [ ] 돈을 눌러 금액을 구성하는 UI
- [ ] 캐릭터 애니메이션과 화면 전환
- [ ] 네트워크 오류 시 입력값 임시 보존

단, 조작 결과가 교육적으로 어떤 의미인지, 다음 지원 단계가 무엇인지는 백엔드 응답을 따릅니다.

---

## 18. 함께 맞추면 되는 최종 연결 순서

1. 프론트에서 사용할 TypeScript `TurnContract` 타입 확정
2. FastAPI OpenAPI 문서와 프론트 타입 대조
3. Next.js 프록시 라우트 연결
4. 집의 모르미 가르치기 한 시나리오부터 연결
5. 부분 답변·잘 모르겠어·오답·중복 탭 E2E 확인
6. 도움 카드와 별노트 연결
7. 카페 1단계 줄 서기 연결
8. 메뉴값·거스름돈·종합 실습 순으로 확장

처음부터 전체 화면을 한꺼번에 바꾸기보다, **집 가르치기 한 시나리오에서 턴 계약을 먼저 안정화한 뒤 같은 계약을 카페에 재사용**하는 방향을 제안합니다.

---

## 19. 한 문장 요약

> 프론트는 아이가 보고 누르고 말할 수 있는 화면을 책임지고, 백엔드는 아이가 무엇을 알고 어디서 막혔는지 판단해 다음 대화·입력 방식·도움 카드·별노트를 하나의 턴 계약으로 전달합니다.
