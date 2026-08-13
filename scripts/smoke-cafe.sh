#!/usr/bin/env bash
# 카페 플로우 스모크 테스트: 5세션 완료 → 해금 → 방문 → 4단계(대화+제출) → 완료.
#
# 각 단계마다 AI 대화를 열고(BE→AI), 그다음 BE에 단계 답안을 제출한다.
# 화면 없이 카페 한 바퀴를 돌려 어디서 끊기는지 찾는다.
#
# 준비는 smoke-dialogue.sh 주석과 같다. 실행: ./scripts/smoke-cafe.sh
#
# 카페 해금은 필수 5세션(number-count, number-compare, money-count,
# money-price, money-budget)을 끝내야 열린다. 그래서 앞부분이 길다.
# 여기서는 반복문제 없이 세션을 시작·완료만 해서 해금 조건을 채운다.

set -uo pipefail

BE="${BE_URL:-http://localhost:8081}"
AI="${AI_URL:-http://localhost:8000}"
CODE="${RESEARCH_CODE:-cafe-$(date +%s)}"

RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; OFF=$'\033[0m'

# 카페 판정에 쓰이는 서버 고정값 (CurriculumCatalog 와 같아야 한다)
#
# 줄 인원은 1~9. BE DTO(@Max 9)·AI 스키마(le=9)·AI KOREAN_COUNTS 가 모두 같은
# 범위를 덮는다. QUEUE_LEFT/QUEUE_RIGHT 로 바꿔가며 경계값을 확인할 수 있다.
LEFT="${QUEUE_LEFT:-3}"; RIGHT="${QUEUE_RIGHT:-7}"
SHORTER=$((LEFT < RIGHT ? LEFT : RIGHT))
BUDGET=9000                     # 허용 예산 8000/9000/10000 중 하나
MORMI_MENU="americano"          # 3000원
CHILD_MENU="milk"               # 2000원  → 합계 5000 ≤ 9000
PAY_TOTAL=5000                  # 계산 단계 정답
CHANGE_MENU="americano"         # 거스름돈 = 10000 - 3000
CHANGE_1000=7                   # 1000원 7장 = 7000

CAFE_CONTEXT="{\"menu_items\":[
  {\"id\":\"americano\",\"name\":\"아메리카노\",\"price\":3000},
  {\"id\":\"milk\",\"name\":\"우유\",\"price\":2000},
  {\"id\":\"cookie\",\"name\":\"쿠키\",\"price\":2000},
  {\"id\":\"sandwich\",\"name\":\"샌드위치\",\"price\":5000}
],\"mormi_menu_id\":\"$MORMI_MENU\",\"budget\":$BUDGET}"

jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for key in '$1'.split('.'):
    d = d.get(key) if isinstance(d, dict) else None
    if d is None:
        sys.exit(0)
print(d)
"; }

# call <이름> <기대상태> <메서드> <경로> [본문]
call() {
    local label="$1" expect="$2" method="$3" path="$4" body="${5:-}"
    local args=(-s -w '\n%{http_code}' -X "$method" "$BE$path" --max-time 120)
    [[ -n "${TOKEN:-}" ]] && args+=(-H "authorization: Bearer $TOKEN")
    [[ -n "$body" ]] && args+=(-H 'content-type: application/json' -d "$body")

    local raw status
    raw=$(curl "${args[@]}")
    status=$(tail -1 <<<"$raw")
    RESPONSE=$(sed '$d' <<<"$raw")

    if [[ "$status" == "$expect" ]]; then
        printf '%s✓%s %-36s %s\n' "$GREEN" "$OFF" "$label" "$status"
        return 0
    fi
    printf '%s✗%s %-36s %s (기대 %s)\n' "$RED" "$OFF" "$label" "$status" "$expect"
    echo "${DIM}$(head -c 900 <<<"$RESPONSE")${OFF}"
    echo
    echo "여기서 멈춥니다. 위 code 의 의미는 docs/ERROR_CODES.md 를 보세요."
    exit 1
}

# stage <번호> <시나리오> <컨텍스트키> <컨텍스트> <제출경로> <제출본문>
# 대화를 먼저 열고(BE→AI), 그다음 같은 단계의 답안을 제출한다.
stage() {
    local no="$1" scenario="$2" ctx_key="$3" ctx="$4" submit_path="$5" submit_body="$6"
    call "$no-a. 대화 시작 ($scenario)" 201 POST "/v1/cafe-visits/$VISIT/dialogues" \
        "{\"scenario_id\":\"$scenario\",\"$ctx_key\":$ctx}"
    echo "   ${DIM}모르미: $(jget turn.mormi.text <<<"$RESPONSE")${OFF}"
    echo "   ${DIM}단계: $(jget stage_progress.stage <<<"$RESPONSE") · 다음: $(jget stage_progress.next_stage <<<"$RESPONSE") · 출처: $(jget stage_progress.source <<<"$RESPONSE")${OFF}"

    call "$no-b. 단계 답안 제출" 200 POST "/v1/cafe-visits/$VISIT$submit_path" "$submit_body"
    echo "   ${DIM}정답: $(jget is_correct <<<"$RESPONSE") · 다음 단계: $(jget next_stage <<<"$RESPONSE") · 해금: $(jget next_stage_unlocked <<<"$RESPONSE")${OFF}"
}

echo "BE=$BE   AI=$AI   연구코드=$CODE"
echo

curl -sf "$BE/health" >/dev/null || { echo "${RED}BE 미기동: $BE${OFF}"; exit 1; }
curl -sf "$AI/health" >/dev/null || { echo "${RED}AI 미기동: $AI${OFF}"; exit 1; }
printf '%s✓%s %-36s %s\n' "$GREEN" "$OFF" "0. BE·AI 기동 확인" "ok"

call "1. 온보딩" 201 POST /v1/learners \
    "{\"display_name\":\"카페\",\"research_code\":\"$CODE\"}"
TOKEN=$(jget access_token <<<"$RESPONSE")

# 해금 조건 채우기. 카페 검증이 목적이므로 반복문제는 생략한다.
for session in number-count number-compare money-count money-price money-budget; do
    call "2. 해금용 세션 ($session)" 201 POST /v1/learning-sessions \
        "{\"curriculum_session_id\":\"$session\",\"variant_seed\":1}"
    SID=$(jget learning_session_id <<<"$RESPONSE")
    call "   └ 완료" 200 POST "/v1/learning-sessions/$SID/complete" \
        '{"transfer_solved":false,"timed_out":false,"elapsed_seconds":60}'
done

call "3. 해금 확인" 200 GET /v1/progress
UNLOCKED=$(jget cafe_unlocked <<<"$RESPONSE")
echo "   ${DIM}cafe_unlocked=$UNLOCKED${OFF}"
[[ "$UNLOCKED" == "True" || "$UNLOCKED" == "true" ]] || {
    echo "${RED}카페가 해금되지 않았습니다. 필수 세션 목록이 바뀌었는지 확인하세요.${OFF}"; exit 1; }

call "4. 카페 방문 시작" 201 POST /v1/cafe-visits
VISIT=$(jget cafe_visit_id <<<"$RESPONSE")
echo "   ${DIM}방문 $VISIT · 소지금 $(jget target_amount <<<"$RESPONSE")원${OFF}"

stage 5 cafe_queue queue_context "{\"left_count\":$LEFT,\"right_count\":$RIGHT}" \
    /queue "{\"left_count\":$LEFT,\"right_count\":$RIGHT,\"chosen_count\":$SHORTER,\"scaffold_used\":false,\"attempt_no\":1,\"elapsed_ms\":3000}"

stage 6 cafe_budget_menu cafe_context "$CAFE_CONTEXT" \
    /menu "{\"menu_ids\":[\"$MORMI_MENU\",\"$CHILD_MENU\"],\"budget\":$BUDGET,\"attempt_no\":1,\"elapsed_ms\":3000}"

stage 7 cafe_menu_total cafe_context "$CAFE_CONTEXT" \
    /payments "{\"menu_ids\":[\"$MORMI_MENU\",\"$CHILD_MENU\"],\"answer_amount\":$PAY_TOTAL,\"attempt_no\":1,\"elapsed_ms\":3000}"

stage 8 cafe_change cafe_context "$CAFE_CONTEXT" \
    /change "{\"menu_id\":\"$CHANGE_MENU\",\"counts\":{\"1000\":$CHANGE_1000},\"attempt_no\":1,\"elapsed_ms\":3000}"

call "9. 카페 방문 완료" 200 POST "/v1/cafe-visits/$VISIT/complete"
echo "   ${DIM}단계: $(jget stage <<<"$RESPONSE") · 주문 $(jget order_total <<<"$RESPONSE")원 · 거스름돈 $(jget change_amount <<<"$RESPONSE")원${OFF}"

call "10. 진도 조회" 200 GET /v1/progress
echo "   ${DIM}지갑 $(jget wallet_balance <<<"$RESPONSE")원 · 진행중 방문 $(jget active_cafe_visit_id <<<"$RESPONSE")${OFF}"

echo
echo "${GREEN}통과${OFF} — 해금부터 카페 방문 완료까지 한 바퀴가 돕니다."
