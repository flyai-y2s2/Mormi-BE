-- 놀이동산 생활수학 3스테이지(#29). 카페와 같은 방문·시도 원장 구조를 쓰되,
-- 문제 숫자를 방문 시작 시 한 번 고정해 facts 에 저장한다. 같은 visit_id 안에서는 바뀌지 않는다.
CREATE TABLE amusement_park_visits (
    id           BIGSERIAL   PRIMARY KEY,
    public_id    VARCHAR(60) NOT NULL,
    learner_id   BIGINT      NOT NULL REFERENCES learners (id),
    stage        VARCHAR(30) NOT NULL DEFAULT 'ticket',
    facts        JSONB       NOT NULL DEFAULT '{}'::JSONB,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_amusement_park_visits_public_id UNIQUE (public_id)
);

CREATE INDEX idx_amusement_park_visits_learner ON amusement_park_visits (learner_id);

-- 스테이지별 시도 기록. payload 에는 주어진 값·아이가 구한 값·서버 정답만 넣는다.
CREATE TABLE amusement_park_visit_stages (
    id            BIGSERIAL   PRIMARY KEY,
    park_visit_id BIGINT      NOT NULL REFERENCES amusement_park_visits (id),
    stage         VARCHAR(30) NOT NULL,
    attempt_no    INTEGER     NOT NULL,
    is_correct    BOOLEAN     NOT NULL,
    elapsed_ms    INTEGER,
    payload       JSONB       NOT NULL DEFAULT '{}'::JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_amusement_park_visit_stage_attempt UNIQUE (park_visit_id, stage, attempt_no)
);

CREATE INDEX idx_amusement_park_visit_stages_visit
    ON amusement_park_visit_stages (park_visit_id);

-- 대화 소유권을 놀이동산 방문에도 붙인다. 카페와 마찬가지로 (방문, 시나리오, 회차)당 하나다.
ALTER TABLE dialogue_conversations
    ADD COLUMN park_visit_id BIGINT REFERENCES amusement_park_visits (id);

CREATE UNIQUE INDEX uq_dialogue_park_scenario_round
    ON dialogue_conversations (park_visit_id, scenario_id, round)
    WHERE park_visit_id IS NOT NULL;
