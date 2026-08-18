-- 문제 1개에 대한 수행 결과를 리포트가 바로 읽을 수 있게 집계해 둔다.
-- 값은 attempts 와 learning_observations 에서 파생하며 언제 다시 계산해도 같은 결과가 나온다.
-- 관찰 이벤트는 늦게 도착할 수 있으므로 (learning_session_id, task_key) 로 upsert 한다.

CREATE TABLE learning_task_outcomes (
    id                        BIGSERIAL    PRIMARY KEY,
    learner_id                BIGINT       NOT NULL REFERENCES learners (id),
    learning_session_id       BIGINT       NOT NULL REFERENCES learning_sessions (id),
    -- attempts.item_id 와 같은 값. 식별자의 주인은 BE 이고 AI 는 이 값을 task_id 로 돌려준다.
    task_key                  VARCHAR(120) NOT NULL,
    activity                  VARCHAR(20),

    -- attempts 파생. 시도 기록이 없으면 NULL 이며 0 과 뜻이 다르다.
    attempt_count             INTEGER,
    wrong_attempt_count       INTEGER,
    first_try_success         BOOLEAN,
    retry_success             BOOLEAN,
    -- 도움 사용 여부는 관찰에서만 알 수 있다. 관찰이 없으면 NULL 로 둔다.
    success_after_help        BOOLEAN,

    -- observations 파생. 발화사다리는 L4(독립)→L0(최대 지원), 힌트사다리는 H0(없음)→H3(최대).
    expression_start          VARCHAR(4),
    expression_end            VARCHAR(4),
    -- 가장 많이 내려간 발화 단계. 지원이 최대로 필요했던 순간을 뜻한다.
    expression_lowest         VARCHAR(4),
    hint_start                VARCHAR(4),
    hint_end                  VARCHAR(4),
    hint_max                  VARCHAR(4),

    -- independent | supported | joint | system_failure | incomplete
    completion_outcome        VARCHAR(30),
    -- 시스템 오류·fallback 은 아동의 수행 실패와 따로 센다.
    system_failure            BOOLEAN,

    bottleneck_candidate      VARCHAR(60),
    -- 1이면 한 번만 관찰된 것이다. 반복 관찰과 구분해야 오개념으로 단정하지 않는다.
    bottleneck_evidence_count INTEGER,

    -- 별노트는 Mormi-AI 가 보유한다. FK 없이 참조 값만 둔다.
    star_note_id              VARCHAR(100),
    star_note_attribution     VARCHAR(20),
    star_note_evidence        VARCHAR(40),

    -- 이 집계가 어떤 기록에서 나왔는지 되짚기 위한 근거 목록.
    source_attempt_ids        BIGINT[]     NOT NULL DEFAULT '{}',
    source_observation_ids    BIGINT[]     NOT NULL DEFAULT '{}',

    -- 집계 규칙이 바뀌면 이 값으로 재계산 대상을 찾는다.
    aggregation_rule_version  VARCHAR(20)  NOT NULL,
    computed_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_learning_task_outcomes UNIQUE (learning_session_id, task_key)
);

CREATE INDEX idx_learning_task_outcomes_learner ON learning_task_outcomes (learner_id);
CREATE INDEX idx_learning_task_outcomes_session ON learning_task_outcomes (learning_session_id);

-- 특정 관찰을 근거로 쓴 집계를 되짚을 수 있게 한다.
CREATE INDEX idx_learning_task_outcomes_sources
    ON learning_task_outcomes USING GIN (source_observation_ids);
