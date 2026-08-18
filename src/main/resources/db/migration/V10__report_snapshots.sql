-- 리포트 1회 생성분을 근거와 함께 고정해 둔다.
-- 리포트 문장은 저장 사실에서 파생하며, 어떤 관찰·시도를 근거로 어떤 규칙 버전으로
-- 만들었는지 되짚을 수 있어야 한다. LLM 문장이 없어도 body 만으로 리포트가 성립한다.

CREATE TABLE report_snapshots (
    id                       BIGSERIAL    PRIMARY KEY,
    -- 대상은 아이 한 명 또는 학급 하나. 정확히 하나만 채운다.
    learner_id               BIGINT       REFERENCES learners (id),
    cohort_id                BIGINT       REFERENCES cohorts (id),
    period_start             TIMESTAMPTZ  NOT NULL,
    period_end               TIMESTAMPTZ  NOT NULL,
    -- LLM 없이 성립하는 구조화 리포트 본문. 문제별 수치·사다리·병목·근거 ID 를 담는다.
    body                     JSONB        NOT NULL,
    -- 근거 사슬: 스냅샷 -> 집계 -> 시도/관찰. 재계산돼도 이 스냅샷의 근거는 고정된다.
    source_observation_ids   BIGINT[]     NOT NULL DEFAULT '{}',
    source_attempt_ids       BIGINT[]     NOT NULL DEFAULT '{}',
    source_outcome_ids       BIGINT[]     NOT NULL DEFAULT '{}',
    aggregation_rule_version VARCHAR(20)  NOT NULL,
    -- NULL 이면 LLM 을 쓰지 않고 만든 스냅샷이다.
    llm_model                VARCHAR(60),
    llm_prompt_version       VARCHAR(40),
    generated_text           TEXT,
    teacher_edited_text      TEXT,
    -- draft | edited | approved
    approval_status          VARCHAR(20)  NOT NULL DEFAULT 'draft',
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_report_snapshots_target CHECK (
        (learner_id IS NOT NULL AND cohort_id IS NULL)
        OR (learner_id IS NULL AND cohort_id IS NOT NULL)),
    CONSTRAINT ck_report_snapshots_period CHECK (period_start <= period_end),
    CONSTRAINT ck_report_snapshots_status CHECK (
        approval_status IN ('draft', 'edited', 'approved'))
);

CREATE INDEX idx_report_snapshots_learner ON report_snapshots (learner_id);
CREATE INDEX idx_report_snapshots_cohort ON report_snapshots (cohort_id);
