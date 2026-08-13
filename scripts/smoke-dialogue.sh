#!/usr/bin/env bash
# 집 가르치기 플로우 스모크 테스트: 온보딩 → 반복문제 → BE→AI 대화 → 완료 → 진도.
#
# 프런트 없이 BE와 AI만으로 한 바퀴를 돌려 어디서 끊기는지 찾는다.
# 실패한 단계에서 즉시 멈추고 응답 본문을 보여준다.
#
# 준비 (터미널 3개):
#   1) docker run -d --name mormi-smoke-pg -e POSTGRES_DB=mormi -e POSTGRES_USER=mormi \
#        -e POSTGRES_PASSWORD=mormi -p 5432:5432 postgres:16
#   2) cd Mormi-AI && MORMI_SERVICE_API_KEY=smoke-key .venv/bin/python -m uvicorn mormi_api.main:app --port 8000
#   3) cd Mormi-BE && DB_USERNAME=mormi DB_PASSWORD=mormi ./gradlew bootRun --args="\
#        --server.port=8081 --mormi.dialogue.base-url=http://localhost:8000 \
#        --mormi.dialogue.service-key=smoke-key"
#
# 실행: ./scripts/smoke-dialogue.sh

set -uo pipefail

BE="${BE_URL:-http://localhost:8081}"
AI="${AI_URL:-http://localhost:8000}"
SESSION_ID="${CURRICULUM_SESSION_ID:-money-count}"
MASTERY_TARGET="${MASTERY_TARGET:-5}"
CODE="${RESEARCH_CODE:-smoke-$(date +%s)}"

RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; OFF=$'\033[0m'
STEP=0

jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for key in '$1'.split('.'):
    if isinstance(d, dict):
        d = d.get(key)
    else:
        d = None
    if d is None:
        sys.exit(0)
print(d)
"; }

# call <이름> <기대상태> <메서드> <경로> [본문]
call() {
    local label="$1" expect="$2" method="$3" path="$4" body="${5:-}"
    STEP=$((STEP + 1))
    local args=(-s -w '\n%{http_code}' -X "$method" "$BE$path" --max-time 120)
    [[ -n "${TOKEN:-}" ]] && args+=(-H "authorization: Bearer $TOKEN")
    [[ -n "$body" ]] && args+=(-H 'content-type: application/json' -d "$body")

    local raw status
    raw=$(curl "${args[@]}")
    status=$(tail -1 <<<"$raw")
    RESPONSE=$(sed '$d' <<<"$raw")

    if [[ "$status" == "$expect" ]]; then
        printf '%s✓%s %-34s %s\n' "$GREEN" "$OFF" "$label" "$status"
        return 0
    fi

    printf '%s✗%s %-34s %s (기대 %s)\n' "$RED" "$OFF" "$label" "$status" "$expect"
    echo "${DIM}$(head -c 900 <<<"$RESPONSE")${OFF}"
    echo
    echo "여기서 멈춥니다. 위 code 의 의미는 docs/ERROR_CODES.md 를 보세요."
    exit 1
}

echo "BE=$BE   AI=$AI   연구코드=$CODE"
echo

# 0. 두 서버가 살아 있는지부터. 여기서 실패하면 아래는 볼 필요가 없다.
curl -sf "$BE/health" >/dev/null || { echo "${RED}BE 미기동: $BE${OFF}"; exit 1; }
curl -sf "$AI/health" >/dev/null || { echo "${RED}AI 미기동: $AI${OFF}"; exit 1; }
printf '%s✓%s %-34s %s\n' "$GREEN" "$OFF" "0. BE·AI 기동 확인" "ok"

call "1. 온보딩" 201 POST /v1/learners \
    "{\"display_name\":\"스모크\",\"research_code\":\"$CODE\"}"
TOKEN=$(jget access_token <<<"$RESPONSE")
[[ -n "$TOKEN" ]] || { echo "${RED}access_token 이 응답에 없습니다${OFF}"; exit 1; }

call "2. 학습 세션 시작" 201 POST /v1/learning-sessions \
    "{\"curriculum_session_id\":\"$SESSION_ID\",\"variant_seed\":1}"
LEARNING_SESSION=$(jget learning_session_id <<<"$RESPONSE")

# 가르치기는 반복문제를 MASTERY_TARGET 개 맞혀야 열린다(drill_not_completed).
for ((i = 0; i < MASTERY_TARGET; i++)); do
    call "3.$((i + 1)) 반복문제 q$i" 201 POST "/v1/learning-sessions/$LEARNING_SESSION/attempts" \
        "{\"activity\":\"drill\",\"attempt_no\":$((i + 1)),\"item_id\":\"q$i\",\"question_index\":$i,\"is_correct\":true,\"elapsed_ms\":3000}"
done

# 여기가 BE→AI 왕복이다. practice_result 등록과 대화 생성이 한 번에 일어난다.
call "4. 가르치기 시작 (BE→AI)" 201 POST "/v1/learning-sessions/$LEARNING_SESSION/teaching"
CONVERSATION=$(jget conversation_id <<<"$RESPONSE")
TURN=$(jget turn.turn_id <<<"$RESPONSE")
echo "   ${DIM}모르미: $(jget turn.mormi.text <<<"$RESPONSE")${OFF}"

call "5. 아이 응답 제출 (LLM 분류)" 200 POST "/v1/dialogue/conversations/$CONVERSATION/responses" \
    "{\"turn_id\":\"$TURN\",\"response_id\":\"$(uuidgen | tr 'A-Z' 'a-z')\",\"type\":\"text\",\"text\":\"오백원이랑 백원이니까 육백원이야\",\"choice_ids\":[],\"values\":{},\"latency_ms\":4000}"
echo "   ${DIM}모르미: $(jget turn.mormi.text <<<"$RESPONSE")${OFF}"
echo "   ${DIM}상태: $(jget turn.status <<<"$RESPONSE")${OFF}"

call "6. 대화 복구 조회" 200 GET "/v1/dialogue/conversations/$CONVERSATION"

call "7. 세션 완료 + 보상 정산" 200 POST "/v1/learning-sessions/$LEARNING_SESSION/complete" \
    "{\"conversation_id\":\"$CONVERSATION\",\"transfer_solved\":false,\"timed_out\":false,\"elapsed_seconds\":120}"
echo "   ${DIM}반복 $(jget drill_reward <<<"$RESPONSE")원 · 가르치기 $(jget teach_reward <<<"$RESPONSE")원 · 지갑 $(jget wallet_balance <<<"$RESPONSE")원${OFF}"

call "8. 진도 조회" 200 GET /v1/progress
echo "   ${DIM}레벨 $(jget level <<<"$RESPONSE") · 별 $(jget stars <<<"$RESPONSE") · 카페해금 $(jget cafe_unlocked <<<"$RESPONSE")${OFF}"

echo
echo "${GREEN}통과${OFF} — 온보딩부터 보상 정산까지 한 바퀴가 돕니다."
echo "${DIM}가르치기 500원은 AI가 대화를 '완료'로 판정한 경우에만 붙습니다."
echo "0원이면 실패가 아니라 아직 안 끝난 대화입니다.${OFF}"
