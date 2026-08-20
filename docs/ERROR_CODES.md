# 오류 코드 표

프런트가 화면 분기를 짜기 위한 문서다. 성공 응답은 OpenAPI(`/v3/api-docs`)에 있으므로
여기서는 다루지 않는다.

## 공통 형식

모든 오류는 같은 모양이다. 프런트는 `message` 를 그대로 아이에게 보여주지 말고
(개발용 문구가 섞여 있다) `code` 로 분기한 뒤 화면에 맞는 문구를 쓴다.

```json
{ "code": "stage_locked", "message": "아직 열리지 않은 단계입니다." }
```

`validation_failed` 만 `fields` 가 추가로 붙는다.

```json
{
  "code": "validation_failed",
  "message": "입력값을 확인해 주세요.",
  "fields": { "displayName": "must not be blank" }
}
```

> ⚠️ **일부 `dialogue_*` 코드는 뒤에 진단 정보가 점으로 붙는다.** (아래 "AI 대화 연동" 참조)
> 프런트는 이 코드들을 **정확히 일치**가 아니라 **접두사 일치**로 처리해야 한다.
> ```ts
> if (code.startsWith("dialogue_invalid_request")) { ... }
> ```

## 인증·권한

| code | status | 언제 | 프런트가 할 일 |
|---|---|---|---|
| `unauthorized` | 401 | 토큰이 없거나 만료·폐기·무효 | 저장된 토큰 지우고 로그인 화면으로 |
| `unauthorized` | 401 | 로그인 실패 (`POST /v1/auth/login`) | "아이디 또는 비밀번호를 확인해 주세요" 입력 화면 유지 |
| `login_id_taken` | 409 | 이미 쓰는 아이디로 가입 (`POST /v1/auth/signup`) | 아이디 입력란에 중복 안내, 다른 아이디 유도 |
| `research_code_taken` | 409 | 이미 등록된 연구 코드로 가입 | 연구 담당자에게 문의 안내 |
| `forbidden` | 403 | 다른 학습자의 세션·방문·대화·데이터 접근 | 재시도 금지. 진도 다시 불러오기 |
| `forbidden` | 403 | 카페가 아직 해금되지 않음 | 재시도 금지. 집 학습으로 안내 |

> ⚠️ **로그인 실패 401 은 아이디가 없는 것과 비밀번호가 틀린 것을 구분해 주지 않는다.**
> 가입 여부를 떠볼 수 없게 하려고 서버가 일부러 같은 응답을 준다. 프런트도 두 경우를
> 나눠 안내할 수 없으므로 한 문구로 처리한다.
>
> ⚠️ **로그아웃·전체 로그아웃 뒤의 401 은 만료와 구분되지 않는다.** 둘 다 `unauthorized`
> 이므로 프런트는 동일하게 로그인 화면으로 보내면 된다.

> ⚠️ 위 두 `forbidden` 은 **코드가 같은데 화면 처리가 정반대다.** 하나는 앱 상태가
> 꼬인 것이고 하나는 정상적인 진행 안내다. 카페 미해금 쪽을 `cafe_locked` 로
> 분리해야 프런트가 구분할 수 있다. (BE 수정 필요)

## 없음

| code | status | 언제 | 프런트가 할 일 |
|---|---|---|---|
| `not_found` | 404 | 등록되지 않은 연구 코드 (deprecated `POST /v1/learners/auth`) | "코드를 다시 확인해 주세요" 입력 화면 유지 |
| `not_found` | 404 | 학습자·학습 세션·카페 방문·대화가 없음 | 진도 다시 불러오기 |
| `not_found` | 404 | 완료된 세션이 없어 리포트가 비어 있음 (`/v1/reports/summary`) | 오류가 아닌 빈 상태 화면 |

> ⚠️ 여기도 셋 다 `not_found` 다. 특히 리포트의 404 는 **오류가 아니라 정상적인
> 빈 상태**인데 프런트는 구분할 수 없다. `research_code_not_found`,
> `report_empty` 로 나누는 편이 낫다. (BE 수정 필요)

## 진행 상태 충돌 (409)

재시도해도 결과가 같다. 그대로 재요청하지 말고 서버 상태를 다시 읽어야 한다.

| code | status | 언제 | 프런트가 할 일 |
|---|---|---|---|
| `session_completed` | 409 | 이미 완료된 학습 세션에 시도 기록·가르치기 시작 | 세션 조회로 상태 동기화 |
| `visit_completed` | 409 | 이미 완료된 카페 방문에 제출 | 방문 조회로 상태 동기화 |
| `cafe_visit_completed` | 409 | 이미 완료된 방문에 대화 시작 요청 | 방문 조회로 상태 동기화 |
| `stage_locked` | 409 | 앞 단계를 통과하지 않고 다음 단계 제출 | 방문 조회 후 올바른 단계로 이동 |
| `change_required` | 409 | 거스름돈 단계를 마치지 않고 방문 완료 요청 | 거스름돈 화면으로 |
| `drill_not_completed` | 409 | 반복 문제 5개를 마치기 전에 가르치기 시작 | 반복 문제 화면 유지 |
| `dialogue_stage_locked` | 409 | 아직 도달하지 않은 카페 단계의 대화 시작 요청 | 방문 조회 후 열린 단계로 이동 |

회원가입의 `login_id_taken`, `research_code_taken` 도 409 지만 서버 상태 동기화가 아니라
입력을 고쳐야 하는 경우다. 위 "인증·권한" 표를 본다.

## 입력값 (400 / 422)

| code | status | 언제 | 프런트가 할 일 |
|---|---|---|---|
| `validation_failed` | 422 | `@Valid` 검증 실패. `fields` 에 필드별 사유 | 입력 보존하고 해당 필드만 표시 |
| `menu_count` | 400 | 메뉴를 두 개가 아닌 개수로 제출 | 선택 UI 자체를 두 개로 제한 |
| `menu_duplicate` | 400 | 같은 메뉴 두 개를 제출 | 선택 UI에서 같은 메뉴 재선택 차단 |
| `menu_unknown` | 400 | 서버 카탈로그에 없는 메뉴 ID | 화면 버그. 메뉴 목록 동기화 |
| `menu_price_mismatch` | 400 | 대화 시작 메뉴판 가격이 서버 가격표와 다름 | 화면 버그. 가격표 동기화 |
| `menu_items_duplicate` | 400 | 대화 시작 메뉴판에 같은 메뉴 ID 중복 | 화면 버그. 메뉴판 구성 확인 |
| `mormi_menu_unknown` | 400 | `mormi_menu_id` 가 메뉴판에 없음 | 화면 버그. 문제 다시 뽑기 |
| `queue_count_range` | 400 | 줄 인원이 1~5 범위를 벗어남 | 화면 버그. 문제 다시 뽑기 |
| `queue_count_equal` | 400 | 좌우 줄 인원이 같음 | 화면 버그. 문제 다시 뽑기 |
| `budget` | 400 | 허용 목록(7000/8000, 구버전 저장분 9000/10000 한시 허용)에 없는 예산값 | 화면 버그. 문제 다시 뽑기 |
| `denomination` | 400 | 존재하지 않는 화폐 액면가 | 화면 버그. 화폐 목록 확인 |
| `count_range` | 400 | 화폐 개수가 0~20 범위를 벗어남 | 입력 UI에서 미리 제한 |
| `invalid_cursor` | 422 | 별노트 목록에 모르는 `cursor` 를 보냄 | 커서 버리고 첫 페이지부터 다시 조회 |
| `queue_context_required` | 400 | 줄 서기 대화 시작에 `queue_context` 누락 | 화면 버그. 좌우 인원 함께 전송 |
| `cafe_context_required` | 400 | 메뉴 대화 시작에 `cafe_context` 누락 | 화면 버그. 메뉴 목록 함께 전송 |
| `dialogue_scenario_invalid` | 400 | 지원하지 않는 카페 시나리오 id | 화면 버그. 시나리오 id 확인 |
| `invalid_request` | 400 | 그 밖의 잘못된 요청 | 개발 로그만 남기고 일반 오류 표시 |

## 학습 세션 (이슈 #6 추가분)

| code | status | 언제 | 프런트가 할 일 |
|---|---|---|---|
| `application_scope_not_allowed` | 400 | `application_scope` 를 transfer 가 아닌 시도에 보냄 | 재시도 금지. 요청 구성 버그 |

`application_scope` 값이 목록(`same_form_new_number`, `new_representation`, `real_life_context`) 밖이면
`validation_failed` (422) 로 떨어진다.

## AI 대화 연동 (`dialogue_*`)

BE가 Mormi-AI를 부르다 실패한 경우다. **대부분 503이고 재시도 가능**하다.
아이 입력은 지우지 말고 "잠시 후 다시" 안내를 띄운다.

### 설정 문제 (배포 직후 여기부터 의심)

| code | status | 원인 |
|---|---|---|
| `dialogue_not_configured` | 503 | BE에 `MORMI_DIALOGUE_BASE_URL` 미설정 |
| `dialogue_key_not_configured` | 503 | BE에 `MORMI_DIALOGUE_SERVICE_KEY` 미설정 |
| `dialogue_auth_failed` | 503 | AI가 401/403. BE·AI의 서비스 키 불일치 |

### 호출 실패

| code | status | 원인 | 프런트가 할 일 |
|---|---|---|---|
| `dialogue_turn_conflict` | 409 | AI가 409. 이미 처리됐거나 이전 턴에 답함 | 최신 대화 조회 후 그 턴을 적용 |
| `dialogue_rate_limited` | 503 | AI가 429 | 잠시 후 재시도 |
| `dialogue_unavailable` | 503 | AI에 연결 실패 | 잠시 후 재시도 |
| `dialogue_upstream_error` | 503 | 그 밖의 AI 오류 | 잠시 후 재시도 |
| `dialogue_ai_error{.진단코드}` | 503 | AI가 5xx. 뒤에 AI 코드가 붙는다 | 잠시 후 재시도 |
| `dialogue_invalid_request{.진단정보}` | 400 | AI가 400/422로 요청을 거절 | **재시도 금지.** 개발 로그 확인 |

`dialogue_invalid_request` 는 이런 모양으로 늘어난다:

```
dialogue_invalid_request.upstream_422.detail_object.home_practice_result_missing.body.practice_result_id
```

`.` 뒤는 원인 추적용이며 **화면 분기에 쓰지 않는다.** 이 코드가 뜨면 BE가 AI에
보내는 요청이 잘못된 것이므로 프런트가 재시도해도 똑같이 실패한다.

### 응답 검증 실패

AI가 200을 줬지만 내용이 계약과 다른 경우다. 전부 503이다.

| code | 원인 |
|---|---|
| `dialogue_invalid_response` | `conversation_id` 나 `turn` 이 없음 |
| `dialogue_completion_facts_missing` | 완료했는데 `verified_facts` 가 없음 |
| `dialogue_completion_fact_invalid` | `verified_facts` 값이 숫자·문자로 해석 불가 |
| `dialogue_completion_fact_mismatch` | 검증된 줄 인원이 화면 문제와 다름 |
| `dialogue_context_missing` | 저장해 둔 카페 문제 정보를 못 찾음 |
| `dialogue_context_invalid` | 저장된 카페 문제 정보가 손상됨 |

## 궁금해사전 (`dictionary_*`)

BE가 Mormi-AI의 사전 카드를 중계하다 실패한 경우다. `dialogue_*` 와 달리
**점(.) 진단 접미사가 붙지 않으므로 정확히 일치로 분기하면 된다.**
사전 조회 실패는 학습 진행을 막지 않는다. 사전 화면만 오류 상태로 두고
학습 흐름은 계속 진행한다.

### 설정 문제 (배포 직후 여기부터 의심)

| code | status | 원인 |
|---|---|---|
| `dictionary_not_configured` | 503 | BE에 `MORMI_DIALOGUE_BASE_URL` 미설정 |
| `dictionary_key_not_configured` | 503 | BE에 `MORMI_DIALOGUE_SERVICE_KEY` 미설정 |
| `dictionary_auth_failed` | 503 | AI가 401/403. BE·AI의 서비스 키 불일치 |

### 조회 결과

| code | status | 언제 | 프런트가 할 일 |
|---|---|---|---|
| `dictionary_card_not_found` | 404 | 그 커리큘럼에 승인된 카드가 없음 | **재시도 금지.** 사전 버튼 숨김 또는 빈 상태 |
| `dictionary_version_mismatch` | 409 | 기대한 콘텐츠 버전과 현재 버전이 다름 | 버전 파라미터 없이 다시 조회해 새 카드로 갱신 |
| `dictionary_snapshot_unavailable` | 409 | 구버전 대화에 고정 스냅샷이 없음 | 세션 경로(최신 카드)로 대체 조회 |
| `dictionary_rate_limited` | 503 | AI가 429 | 잠시 후 재시도 |
| `dictionary_ai_error` | 503 | AI 5xx. BE가 1회 재시도한 뒤의 결과 | 잠시 후 재시도 |
| `dictionary_unavailable` | 503 | AI 연결 실패·시간초과. 1회 재시도 후 | 잠시 후 재시도 |
| `dictionary_upstream_error` | 503 | 그 밖의 AI 오류 | 잠시 후 재시도 |

대화 스냅샷 조회에서 AI에 대화 자체가 없으면 위 코드가 아니라 일반 `not_found` 404 로
온다. BE에 소유 기록이 있는데 AI가 대화를 모르는 비정상 상황이므로, 화면은 "없음" 표의
대화 없음과 같게 처리한다.

## 서버

| code | status | 프런트가 할 일 |
|---|---|---|
| `internal_error` | 500 | 잠시 후 재시도 안내. 아이 입력은 보존 |

## 프런트 프록시가 만드는 코드

BE가 아니라 Next 서버(`/api/be`)가 붙이는 코드다. BE에 도달하지 못한 경우다.

| code | status | 의미 |
|---|---|---|
| `backend_not_configured` | 503 | `BACKEND_ORIGIN` 미설정 |
| `backend_unavailable` | 503 | BE에 연결 실패 |
| `backend_timeout` | 503 | BE 응답 55초 초과 |
| `request_timeout` | 504 | 브라우저 타임아웃 |
| `network_error` | 503 | fetch 자체 실패 |

## 바꿀 때

이 표에 없는 `code` 를 새로 던지면 프런트는 `http_error` 로 뭉뚱그려 처리한다.
**코드를 추가하거나 의미를 바꾸면 이 문서를 같은 PR에서 함께 고친다.**
