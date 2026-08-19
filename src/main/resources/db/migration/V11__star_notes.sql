-- AI가 확정 발행한 별노트의 서비스 원장. FE 목록 화면의 단일 출처다.
-- 수신함은 V6 observation_events 를 그대로 재사용하고, 파싱에 성공한
-- star_note_created 이벤트만 여기 한 행씩 남는다.
-- 순서 역전 허용: 근거 관찰 이벤트보다 별노트가 먼저 도착할 수 있으므로
-- evidence_links 는 learning_observations 에 FK 를 걸지 않고 AI 원본 ID 문자열
-- 그대로(JSONB) 보관한다. 근거 연결이 필요하면 조회 시점에 ID 로 찾는다.
-- 노트 문장(note_text)과 귀속(attribution)은 AI가 준 원문을 재작성 없이 보존한다.

CREATE TABLE star_notes (
    id                   BIGSERIAL    PRIMARY KEY,
    -- 마지막으로 이 행을 만들거나 갱신한 수신함 이벤트. 원본 payload 추적용.
    observation_event_id BIGINT       NOT NULL REFERENCES observation_events (id),
    -- AI 저장소의 원본 note 추적용. 같은 노트가 다른 이벤트로 재전송돼도 한 행 유지.
    note_id              VARCHAR(100) NOT NULL,
    -- 재발행 판별용. 같거나 낮은 버전이 늦게 도착하면 덮어쓰지 않는다.
    note_version         INTEGER      NOT NULL,
    -- 소유권은 이벤트가 보낸 값이 아니라 대화 기록(dialogue_conversations)에서 끌어온다.
    learner_id           BIGINT       NOT NULL REFERENCES learners (id),
    learning_session_id  BIGINT       REFERENCES learning_sessions (id),
    conversation_id      VARCHAR(100) NOT NULL,
    scene                VARCHAR(40),
    scenario_id          VARCHAR(100),
    task_id              VARCHAR(120),
    stage                VARCHAR(40),
    task_index           INTEGER,
    skill_id             VARCHAR(100),
    -- 아이에게 보여줄 문장. 컬럼명은 타입명과 겹치는 text 를 피했고 API 응답은 text 로 나간다.
    note_text            TEXT         NOT NULL,
    -- child | mormi 등. 값 목록은 AI 이벤트 계약 문서가 관리한다.
    attribution          VARCHAR(20)  NOT NULL,
    attribution_label    VARCHAR(60),
    evidence             VARCHAR(40),
    -- [{"observation_id": "...", "source_slot_ids": [...]}]. 의도적으로 FK 없음(순서 역전 허용).
    evidence_links       JSONB,
    -- 재발행으로 비활성화된 노트는 목록에서 숨긴다. 행은 지우지 않는다.
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    -- AI가 노트를 만든 시각. 이벤트가 늦게 도착해도 이 값으로 정렬해야 순서가 안 꼬인다.
    note_created_at      TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- 같은 노트가 다른 event_id 로 재전송돼도 한 행만 남는다. note_id 멱등성의 최종 방어선.
    CONSTRAINT uq_star_notes_note_id UNIQUE (note_id)
);

-- 학습자별 목록(최신순 keyset 페이지네이션) 전용. 정렬 키와 방향을 그대로 태운다.
CREATE INDEX idx_star_notes_learner_created
    ON star_notes (learner_id, note_created_at DESC, note_id DESC);
