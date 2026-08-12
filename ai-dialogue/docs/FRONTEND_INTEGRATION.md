# 프론트엔드 연동 규격

이 문서는 2026-08-12 프론트엔드 회신에서 합의한 AI 대화 API 계약입니다.

## 연결 구조

```text
브라우저
  → Next.js /api/dialogue/*
  → FastAPI /v1/conversations/*
```

- FastAPI 기본 로컬 주소: `http://localhost:8000`
- API 문서: `http://localhost:8000/docs`
- BFF 인증 헤더: `X-Mormi-Service-Key`
- Next.js 서버 환경 변수: `MORMI_DIALOGUE_SERVICE_KEY`
- 브라우저 번들과 `NEXT_PUBLIC_*`에는 서비스 키를 넣지 않습니다.

## 엔드포인트

| Method | Path | 역할 |
|---|---|---|
| POST | `/v1/practice-results` | MVP용 반복 결과 스냅샷 저장 |
| POST | `/v1/conversations` | 집 가르치기·카페 대화 시작 |
| POST | `/v1/conversations/{conversation_id}/responses` | 아이 응답 제출 |
| GET | `/v1/conversations/{conversation_id}` | 최신 턴 복구 |
| GET | `/v1/learners/{learner_id}/skill-profiles` | 학습자별 시작 L/H 근거 조회 |
| GET | `/v1/learners/{learner_id}/star-notes` | 별노트 조회 |

보호된 분석용 원문은 `/v1/conversations/{conversation_id}/transcript`에서 조회합니다.

## 대화 시작

반복 결과가 AI 대화 DB에 이미 저장되어 있으면 `practice_result_id`만 보내도 됩니다.
저장되어 있지 않은 분리 서비스 MVP에서는 요약 스냅샷을 함께 보냅니다.

```json
{
  "learner_id": 1,
  "scene": "home_teach",
  "scenario_id": "home_addition_teach",
  "learning_session_id": "session_123",
  "practice_result_id": "practice_123",
  "practice_summary": {
    "skill_id": "basic_addition",
    "question_count": 5,
    "first_try_correct_count": 3,
    "wrong_attempt_count": 2,
    "earned_reward": 850,
    "misconception_tags": ["count_all_error"]
  },
  "conversation_storage_consent": true,
  "retention_policy": "30_days"
}
```

`practice_summary`는 값만 담는 스냅샷이므로 `learner_id`와
`practice_result_id`를 내부에 반복하지 않습니다. 소유자와 결과 ID는 바깥 필드를
단일 출처로 사용합니다.

원문 저장 동의가 없으면 다음 두 필드를 사용합니다.

```json
{
  "conversation_storage_consent": false,
  "retention_policy": "no_raw"
}
```

시작 응답과 이후 응답은 모두 동일한 최상위 계약을 사용합니다.

```json
{
  "conversation_id": "conversation_...",
  "turn": {}
}
```

## 응답 제출

프론트는 항상 최신 `turn.turn_id`를 그대로 돌려보냅니다. 한 번의 사용자 행동마다
UUID `response_id`를 하나 생성하고, 네트워크 재시도에는 같은 ID를 사용합니다.

| `turn.input.kind` | 보낼 `type` | 핵심 필드 |
|---|---|---|
| `text` | `text` | `text`, 선택적으로 `asr_confidence` |
| `choices` | `choice` | `choice_ids` |
| `fill` | `fill` | `choice_ids` |
| `count` | `count` | `values`의 슬롯별 센 값·조작 결과 |
| `equation` | `equation` | `values`의 자리별 입력·계산 슬롯 |
| `joint` | `action` | `values`의 공동 수행 완료 단계 |
| `button` | `action` | `values`의 action ID |

```json
{
  "turn_id": "turn_...",
  "response_id": "9cda3c1e-6539-4b35-9ac5-c63f91e203b1",
  "type": "choice",
  "choice_ids": ["left_queue"],
  "latency_ms": 1800
}
```

## TurnContract 렌더링

- `mormi.text`: 최대 50자, 최대 두 줄
- `mormi.mood`: 의미 단위 표정 상태
- `input`: 유일하게 허용된 다음 입력 방식
- `visual`: 장면 중앙 시각 자료
- `help_card`: `auto_open=true`이면 즉시 자동으로 열기
- `note_update`: null이 아닐 때만 별노트에 추가
- `status=completed`: 완료 화면으로 이동
- `completion.teach_reward_eligible`: 일반 학습 백엔드가 500원 지급을 검증할 근거
- `stage_id=remix`: 카페 네 번째 종합 스테이지 UI 사용

프론트는 선택지 정오, L/H 전환, 완료, 노트 귀속을 다시 추론하지 않습니다.

## 완료 계약

```json
{
  "status": "completed",
  "completion": {
    "outcome": "supported",
    "teach_reward_eligible": true
  }
}
```

- `taught`: 독립적인 문장 가르침으로 완료
- `supported`: 선택·조작·도움 카드 지원을 받아 성공적으로 완료
- `bright_exit`: 외부 중단 등으로 안전하게 닫았지만 가르치기 성공은 아님

현재 정상 학습 흐름은 `taught` 또는 `supported`로 닫으며 두 경우 모두 보상 가능
완료입니다. `bright_exit`에는 보상을 지급하지 않습니다.

## 오류 처리

- `409`: 응답의 `detail.turn_id`, `detail.state_version`으로 최신 상태를 확인하거나 GET 복구
- `422`: 입력을 보존하고 필드별 오류를 개발 로그에 기록
- `503`: 상태가 바뀌지 않았으므로 같은 `response_id`로 재시도
- 네트워크 오류: 마지막 성공 턴과 아이 입력을 유지
- 중복 탭: 전송 즉시 버튼 잠금

동일 `response_id`는 기본 30일 동안 최초 결과 턴과 연결됩니다. 대화 응답은 다음
질문이 필요하므로 오프라인 큐에서 나중에 일괄 전송하지 않습니다.

## 개인정보

- 아이 원문은 명시적 저장 동의가 있을 때만 암호화 저장합니다.
- 동의가 없으면 분류·검증 슬롯·선택 ID 같은 구조 데이터만 저장합니다.
- 음성 파일은 저장하지 않습니다.
- 이름·원문·별노트 문장을 PostHog에 보내지 않습니다.
- 모르미 질문은 화면 복구를 위해 암호화 저장하며 턴 JSON에 평문으로 중복 저장하지 않습니다.

## 프론트 마이그레이션

1. 현재 `/api/morami/respond`를 유지한 채 내부 구현을 FastAPI 프록시로 교체
2. 새 BFF 경로 `/api/dialogue/*`를 추가
3. 집 가르치기 한 시나리오를 TurnContract로 연결
4. 기존 숫자 `3~0` 판정과 정적 별노트를 제거
5. 카페 줄 서기부터 같은 계약으로 확장

호환 기간이 끝나면 `/api/morami/respond`를 제거하고 공식 표기를 `mormi`로 통일합니다.
