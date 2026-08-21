-- 참여 번호 사전 발급 장부. 교사가 학급 화면에서 번호를 미리 발급하고
-- 아이는 가입 폼에서 그 번호를 입력한다. 서버가 이 장부에서 학급을 찾아
-- learner_enrollments 행을 만들므로, 아이 가입 폼의 입력 칸은 늘지 않는다.
-- 이미 그 번호로 가입한 학습자가 있으면 발급 시점에 소급 재적된다.
CREATE TABLE cohort_research_codes (
    id         BIGSERIAL    PRIMARY KEY,
    cohort_id  BIGINT       NOT NULL REFERENCES cohorts (id),
    code       VARCHAR(40)  NOT NULL,
    -- 발급 주체. consent_records.collected_by 를 채우는 근거가 된다.
    issued_by  BIGINT       NOT NULL REFERENCES educators (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- 참여 번호는 아이 한 명의 전역 식별자라 학급을 넘어서도 겹치면 안 된다.
    CONSTRAINT uq_cohort_research_codes_code UNIQUE (code)
);

CREATE INDEX idx_cohort_research_codes_cohort ON cohort_research_codes (cohort_id);
