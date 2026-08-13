-- 인증된 학습자와 Mormi-AI 대화의 소유권을 Spring BE가 보관한다.
-- 브라우저가 보낸 learner_id 또는 임의의 conversation_id를 신뢰하지 않는다.
CREATE TABLE dialogue_conversations (
    id                  BIGSERIAL    PRIMARY KEY,
    conversation_id     VARCHAR(100) NOT NULL,
    learner_id          BIGINT       NOT NULL REFERENCES learners (id),
    learning_session_id BIGINT       REFERENCES learning_sessions (id),
    cafe_visit_id       BIGINT       REFERENCES cafe_visits (id),
    scenario_id         VARCHAR(100) NOT NULL,
    scenario_context    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_dialogue_conversation_id UNIQUE (conversation_id),
    CONSTRAINT ck_dialogue_owner_scope CHECK (
        (learning_session_id IS NOT NULL AND cafe_visit_id IS NULL)
        OR (learning_session_id IS NULL AND cafe_visit_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_dialogue_learning_session
    ON dialogue_conversations (learning_session_id)
    WHERE learning_session_id IS NOT NULL;

CREATE UNIQUE INDEX uq_dialogue_cafe_scenario
    ON dialogue_conversations (cafe_visit_id, scenario_id)
    WHERE cafe_visit_id IS NOT NULL;

CREATE INDEX idx_dialogue_learner ON dialogue_conversations (learner_id);
