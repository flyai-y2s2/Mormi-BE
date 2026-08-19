-- AI 관찰 이벤트를 리포트에 쓸 수 있게 두 단계로 저장한다.
-- observation_events: 받은 이벤트를 그대로 보관하는 수신함. 스키마 버전이 달라
--   파싱하지 못한 이벤트도 남겨 두고 나중에 재처리한다.
-- learning_observations: 파싱에 성공한 이벤트의 리포트용 구조화 관찰값.
-- 아이 원문 발화와 암호화 키는 이벤트 계약상 수신하지 않는다.

CREATE TABLE observation_events (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       VARCHAR(100) NOT NULL,
    schema_version VARCHAR(20)  NOT NULL,
    event_type     VARCHAR(40)  NOT NULL,
    payload        JSONB        NOT NULL,
    -- received | processed | failed
    status         VARCHAR(20)  NOT NULL DEFAULT 'received',
    error_message  TEXT,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMPTZ,
    -- 같은 이벤트가 몇 번 도착해도 한 행만 남는다. 멱등성의 최종 방어선.
    CONSTRAINT uq_observation_events_event_id UNIQUE (event_id)
);

-- 실패 이벤트 재처리 조회 전용. 정상 건은 인덱스에 넣지 않는다.
CREATE INDEX idx_observation_events_failed
    ON observation_events (received_at)
    WHERE status = 'failed';

-- 불리언·수치 컬럼의 NULL 은 '수집 안 됨'이다. false/0 과 의미가 다르므로
-- 값이 확인된 경우에만 채운다.
CREATE TABLE learning_observations (
    id                     BIGSERIAL    PRIMARY KEY,
    observation_event_id   BIGINT       NOT NULL REFERENCES observation_events (id),
    -- AI 저장소의 원본 observation 추적용. 재전송 시 새 이벤트가 와도 관찰은 한 행 유지.
    ai_observation_id      VARCHAR(100) NOT NULL,
    ai_observation_version VARCHAR(20),
    learner_id             BIGINT       NOT NULL REFERENCES learners (id),
    learning_session_id    BIGINT       REFERENCES learning_sessions (id),
    cafe_visit_id          BIGINT       REFERENCES cafe_visits (id),
    conversation_id        VARCHAR(100),
    scenario_id            VARCHAR(100),
    task_id                VARCHAR(120),
    stage                  VARCHAR(40),
    turn_index             INTEGER,
    -- 응답 범주·난이도·개념 판정. 값 목록은 이벤트 계약 문서가 관리한다.
    response_category      VARCHAR(40),
    difficulty_class       VARCHAR(40),
    concept_result         VARCHAR(40),
    bottleneck_candidate   VARCHAR(60),
    -- 발화사다리(L4 독립 ~ L0 최대 지원)와 힌트사다리(H0 없음 ~ H3 최대)는 별개 상태값이다.
    -- 표현 막힘은 L만 내려가고 개념 오답은 H만 올라간다. 전후를 각각 남겨 전환을 조회한다.
    expression_before      VARCHAR(4),
    expression_after       VARCHAR(4),
    hint_before            VARCHAR(4),
    hint_after             VARCHAR(4),
    transition_reason      VARCHAR(60),
    help_used              BOOLEAN,
    -- 시스템 실패는 아동 수행 실패와 분리해 집계한다.
    fallback_occurred      BOOLEAN,
    system_error           BOOLEAN,
    -- independent | supported | joint | system_failure 등. 계약 문서가 관리.
    completion_outcome     VARCHAR(30),
    -- 분류기 확신도와 근거 강도는 다른 축이므로 분리 저장한다.
    classifier_confidence  NUMERIC(4, 3),
    evidence_strength      VARCHAR(20),
    observed_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_learning_observations_ai_id UNIQUE (ai_observation_id)
);

CREATE INDEX idx_learning_observations_learner ON learning_observations (learner_id);
CREATE INDEX idx_learning_observations_session ON learning_observations (learning_session_id);
CREATE INDEX idx_learning_observations_cafe_visit ON learning_observations (cafe_visit_id);
