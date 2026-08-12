-- Mormi 일반 학습 백엔드 초기 스키마
-- 아동 데이터 테이블에는 learner_id 인덱스를 둔다.
-- 아이 이름, 자유 발화 원문, 음성은 이 스키마에 저장하지 않는다.
-- 대화 원문은 Mormi-AI 저장소가 암호화 보관한다.

CREATE TABLE learners (
    id                           BIGSERIAL    PRIMARY KEY,
    display_name                 VARCHAR(40)  NOT NULL,
    research_code                VARCHAR(40)  NOT NULL,
    analytics_id                 UUID         NOT NULL,
    token_hash                   VARCHAR(64)  NOT NULL,
    conversation_storage_consent BOOLEAN      NOT NULL DEFAULT FALSE,
    retention_policy             VARCHAR(20)  NOT NULL DEFAULT 'no_raw',
    onboarding_completed_at      TIMESTAMPTZ,
    created_at                   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_learners_research_code UNIQUE (research_code),
    CONSTRAINT uq_learners_analytics_id  UNIQUE (analytics_id),
    CONSTRAINT uq_learners_token_hash    UNIQUE (token_hash)
);

CREATE TABLE learning_sessions (
    id                    BIGSERIAL    PRIMARY KEY,
    public_id             VARCHAR(60)  NOT NULL,
    learner_id            BIGINT       NOT NULL REFERENCES learners (id),
    curriculum_session_id VARCHAR(60)  NOT NULL,
    variant_seed          INTEGER      NOT NULL DEFAULT 0,
    scaffold_level        INTEGER,
    elapsed_seconds       INTEGER,
    transfer_solved       BOOLEAN      NOT NULL DEFAULT FALSE,
    timed_out             BOOLEAN      NOT NULL DEFAULT FALSE,
    conversation_id       VARCHAR(100),
    practice_result_id    VARCHAR(100),
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ,
    CONSTRAINT uq_learning_sessions_public_id UNIQUE (public_id)
);

CREATE INDEX idx_learning_sessions_learner ON learning_sessions (learner_id);
CREATE INDEX idx_learning_sessions_curriculum ON learning_sessions (learner_id, curriculum_session_id);

-- 한 문제에 대한 시도 1건. 오답 상세는 answer_meta 에 구조 데이터로만 남긴다.
CREATE TABLE attempts (
    id                  BIGSERIAL    PRIMARY KEY,
    learning_session_id BIGINT       NOT NULL REFERENCES learning_sessions (id),
    activity            VARCHAR(20)  NOT NULL,
    attempt_no          INTEGER      NOT NULL,
    item_id             VARCHAR(120),
    question_index      INTEGER,
    is_correct          BOOLEAN      NOT NULL,
    elapsed_ms          INTEGER,
    reward_granted      INTEGER      NOT NULL DEFAULT 0,
    answer_meta         JSONB        NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_attempts_idempotent UNIQUE (learning_session_id, activity, attempt_no)
);

CREATE INDEX idx_attempts_session ON attempts (learning_session_id);

CREATE TABLE theme_progress (
    id          BIGSERIAL    PRIMARY KEY,
    learner_id  BIGINT       NOT NULL REFERENCES learners (id),
    theme_id    VARCHAR(40)  NOT NULL,
    unlocked_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_theme_progress UNIQUE (learner_id, theme_id)
);

CREATE INDEX idx_theme_progress_learner ON theme_progress (learner_id);

-- stage = queue | menu | calculate | change | complete
CREATE TABLE cafe_visits (
    id            BIGSERIAL    PRIMARY KEY,
    public_id     VARCHAR(60)  NOT NULL,
    learner_id    BIGINT       NOT NULL REFERENCES learners (id),
    stage         VARCHAR(20)  NOT NULL DEFAULT 'queue',
    target_amount INTEGER      NOT NULL DEFAULT 10000,
    order_total   INTEGER,
    paid_amount   INTEGER,
    change_amount INTEGER,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ,
    CONSTRAINT uq_cafe_visits_public_id UNIQUE (public_id)
);

CREATE INDEX idx_cafe_visits_learner ON cafe_visits (learner_id);

-- 스테이지별 시도 기록. payload 에는 화폐별 개수, 선택한 메뉴 id 같은 구조 데이터만 넣는다.
CREATE TABLE cafe_visit_stages (
    id            BIGSERIAL    PRIMARY KEY,
    cafe_visit_id BIGINT       NOT NULL REFERENCES cafe_visits (id),
    stage         VARCHAR(20)  NOT NULL,
    attempt_no    INTEGER      NOT NULL,
    is_correct    BOOLEAN      NOT NULL,
    elapsed_ms    INTEGER,
    payload       JSONB        NOT NULL DEFAULT '{}'::JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cafe_visit_stage_attempt UNIQUE (cafe_visit_id, stage, attempt_no)
);

CREATE INDEX idx_cafe_visit_stages_visit ON cafe_visit_stages (cafe_visit_id);

-- 지갑 잔액은 별도 컬럼 없이 이 원장의 합계로 도출한다.
CREATE TABLE reward_ledger (
    id                  BIGSERIAL    PRIMARY KEY,
    learner_id          BIGINT       NOT NULL REFERENCES learners (id),
    learning_session_id BIGINT       REFERENCES learning_sessions (id),
    source              VARCHAR(30)  NOT NULL,
    amount              INTEGER      NOT NULL,
    idempotency_key     VARCHAR(160) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reward_ledger_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_reward_ledger_learner ON reward_ledger (learner_id);
