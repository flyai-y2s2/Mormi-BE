// 집 가르치기 대화 부하 시나리오 (k6).
//
// VU 하나 = 아이 하나. 회원가입(/v1/auth/signup) → 학습 세션 → 반복문제 5개 → 가르치기 시작(BE→AI) 후
// TURN_INTERVAL_S 초마다 응답을 보낸다. 대화가 완료되면 세션을 정산하고 새 세션을 연다.
// smoke-dialogue.sh 와 같은 API 순서를 그대로 따른다.
//
//   brew install k6
//   BE_URL=http://<BE 공인IP>:8080 VUS=20 DURATION=10m \
//     k6 run --summary-export=summary.json scripts/loadtest/dialogue-turns.js
//
// 환경변수
//   BE_URL            BE 주소 (기본 http://localhost:8081)
//   VUS               동시 아이 수 (기본 20)
//   DURATION          유지 시간 (기본 10m)
//   RAMP_S            0이면 전원 동시 시작(교실 버스트), N이면 N초에 걸쳐 늘림 (기본 0)
//   TURN_INTERVAL_S   턴 간격 초 (기본 15)
//   JITTER            턴 간격 ±비율 (기본 0.2)
//   MASTERY_TARGET    가르치기 해금에 필요한 반복문제 정답 수 (기본 5)
//   CURRICULUM_SESSION_ID  세션 id (기본 money-count)
//   RUN_TAG           실행 구분자 (기본 실행 시각). 테스트 학습자는 research_code `lt-<RUN_TAG>-<VU>`,
//                     login_id `lt<RUN_TAG>v<VU>`, 비밀번호 loadtest-1234 로 만들어지니 끝나면 정리한다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BE = __ENV.BE_URL || 'http://localhost:8081';
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '10m';
const RAMP_S = Number(__ENV.RAMP_S || 0);
const TURN_INTERVAL_S = Number(__ENV.TURN_INTERVAL_S || 15);
const JITTER = Number(__ENV.JITTER || 0.2);
const MASTERY_TARGET = Number(__ENV.MASTERY_TARGET || 5);
const SESSION_ID = __ENV.CURRICULUM_SESSION_ID || 'money-count';
const RUN_TAG = __ENV.RUN_TAG || String(Date.now()).slice(-8);
const CHILD_TEXT = '오백원이랑 백원이니까 육백원이야';
const REQUEST_TIMEOUT = '120s'; // BE→AI read-timeout(45s) 보다 넉넉히

const scenario = RAMP_S > 0
  ? {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: `${RAMP_S}s`, target: VUS },
        { duration: DURATION, target: VUS },
      ],
      gracefulRampDown: '30s',
    }
  : { executor: 'constant-vus', vus: VUS, duration: DURATION };

export const options = {
  scenarios: { children: scenario },
  // 임계값은 "합격선"이 아니라 결과를 한눈에 보기 위한 기준선이다. 넘으면 k6 가 exit 1.
  thresholds: {
    http_req_failed: ['rate<0.05'],
    mormi_turn_ms: ['p(95)<10000'],
    mormi_teaching_start_ms: ['p(95)<15000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

// 커스텀 메트릭 — 요청 단위가 아니라 "아이가 체감하는 단위"로 본다.
const turnMs = new Trend('mormi_turn_ms', true);              // 응답 제출 → 모르미 답까지
const teachingStartMs = new Trend('mormi_teaching_start_ms', true); // 가르치기 시작(BE→AI 대화 생성)
const turnFailed = new Rate('mormi_turn_failed');
const serverErrors = new Counter('mormi_5xx');
const cycles = new Counter('mormi_cycles');

// k6 는 VU 마다 별도 JS 런타임이라 모듈 변수가 곧 VU 상태다.
let state = null;
let consecutiveFailures = 0;

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function jitter(seconds) {
  if (JITTER <= 0) return seconds;
  return seconds * (1 + (Math.random() * 2 - 1) * JITTER);
}

function request(name, method, path, body, expect) {
  const headers = { 'content-type': 'application/json' };
  if (state && state.token) headers.authorization = `Bearer ${state.token}`;
  const params = { headers, timeout: REQUEST_TIMEOUT, tags: { name } };
  const res = method === 'GET'
    ? http.get(`${BE}${path}`, params)
    : http.post(`${BE}${path}`, body ? JSON.stringify(body) : null, params);
  if (res.status >= 500) serverErrors.add(1, { name });
  const expected = Array.isArray(expect) ? expect : [expect];
  const ok = check(res, { [`${name} ${expected.join('/')}`]: (r) => expected.includes(r.status) });
  if (!ok) {
    console.warn(`[VU ${__VU}] ${name} -> ${res.status} ${String(res.body).slice(0, 200)}`);
    return null;
  }
  try {
    return res.json();
  } catch (e) {
    return {};
  }
}

const PASSWORD = 'loadtest-1234'; // 8~72자

function onboard() {
  // 운영 BE 는 회원가입이 /v1/auth/signup 이고, 응답에 access_token 이 실려 온다.
  const researchCode = `lt-${RUN_TAG}-${__VU}`;               // [A-Za-z0-9._-]+, 40자 이하
  const loginId = `lt${String(RUN_TAG).replace(/[^A-Za-z0-9]/g, '')}v${__VU}`; // [A-Za-z0-9]+, 4~20자
  const signup = request('signup', 'POST', '/v1/auth/signup', {
    display_name: `부하${__VU}`,                                // 12자 이하
    research_code: researchCode,
    login_id: loginId,
    password: PASSWORD,
  }, [200, 201]);
  if (signup && signup.access_token) return signup.access_token;

  // 같은 RUN_TAG 로 다시 돌리면 이미 가입돼 있으니(409) 로그인으로 넘어간다.
  const login = request('login', 'POST', '/v1/auth/login', {
    login_id: loginId,
    password: PASSWORD,
  }, 200);
  return login && login.access_token ? login.access_token : null;
}

let onboardFailures = 0;

// 학습 세션 → 반복문제 → 가르치기 시작. 성공하면 state 에 대화를 심는다.
function startCycle() {
  state.learningSessionId = null;
  state.conversationId = null;
  state.turnId = null;
  state.input = null;

  const session = request('learning-session', 'POST', '/v1/learning-sessions', {
    curriculum_session_id: SESSION_ID,
    variant_seed: 1,
  }, 201);
  if (!session || !session.learning_session_id) return false;
  state.learningSessionId = session.learning_session_id;

  for (let i = 0; i < MASTERY_TARGET; i += 1) {
    const attempt = request('drill-attempt', 'POST',
      `/v1/learning-sessions/${state.learningSessionId}/attempts`, {
        activity: 'drill',
        attempt_no: i + 1,
        item_id: `q${i}`,
        question_index: i,
        is_correct: true,
        elapsed_ms: 3000,
      }, 201);
    if (!attempt) return false;
  }

  const started = Date.now();
  const teaching = request('teaching-start', 'POST',
    `/v1/learning-sessions/${state.learningSessionId}/teaching`, null, 201);
  teachingStartMs.add(Date.now() - started);
  if (!teaching || !teaching.conversation_id || !teaching.turn) return false;

  state.conversationId = teaching.conversation_id;
  state.turnId = teaching.turn.turn_id;
  state.input = teaching.turn.input || null;
  cycles.add(1);
  return true;
}

function responseBody() {
  const base = {
    turn_id: state.turnId,
    response_id: uuid(),
    choice_ids: [],
    values: {},
    latency_ms: 4000,
  };
  const kind = state.input && state.input.kind;
  if (kind === 'choices' && state.input.choices && state.input.choices.length) {
    const first = state.input.choices.find((c) => !c.disabled) || state.input.choices[0];
    return Object.assign(base, { type: 'choice', text: '', choice_ids: [first.id] });
  }
  return Object.assign(base, { type: 'text', text: CHILD_TEXT });
}

function completeSession() {
  request('session-complete', 'POST',
    `/v1/learning-sessions/${state.learningSessionId}/complete`, {
      conversation_id: state.conversationId,
      transfer_solved: false,
      timed_out: false,
      elapsed_seconds: 120,
    }, 200);
}

function doTurn() {
  const started = Date.now();
  const data = request('respond', 'POST',
    `/v1/dialogue/conversations/${state.conversationId}/responses`, responseBody(), 200);
  const elapsed = Date.now() - started;
  turnMs.add(elapsed);
  turnFailed.add(data === null);

  if (data === null) {
    consecutiveFailures += 1;
    if (consecutiveFailures >= 3) {
      console.warn(`[VU ${__VU}] 3 consecutive failures, restarting cycle`);
      consecutiveFailures = 0;
      completeSession();
      startCycle();
    }
    return;
  }
  consecutiveFailures = 0;

  const turn = data.turn || {};
  if (turn.turn_id) state.turnId = turn.turn_id;
  state.input = turn.input || state.input;
  if (turn.status === 'completed') {
    completeSession();
    startCycle();
  }
}

export function setup() {
  const res = http.get(`${BE}/health`, { timeout: '10s' });
  if (res.status !== 200) {
    throw new Error(`BE 미기동: ${BE}/health -> ${res.status}`);
  }
  console.log(`BE=${BE} VUS=${VUS} DURATION=${DURATION} RAMP_S=${RAMP_S} TURN_INTERVAL_S=${TURN_INTERVAL_S} RUN_TAG=${RUN_TAG}`);
}

export default function () {
  if (!state) {
    const token = onboard();
    if (!token) {
      // 온보딩이 안 되면 설정 문제다. 헛돌지 말고 세 번 만에 테스트를 멈춘다.
      onboardFailures += 1;
      if (onboardFailures >= 3) {
        exec.test.abort(`[VU ${__VU}] onboarding failed 3 times — 위 WARN 줄의 상태 코드·본문을 확인`);
      }
      sleep(3);
      return;
    }
    state = { token };
    startCycle();
  } else if (!state.conversationId) {
    startCycle();
  } else {
    doTurn();
  }
  sleep(jitter(TURN_INTERVAL_S));
}
