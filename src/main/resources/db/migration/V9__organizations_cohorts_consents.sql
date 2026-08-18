-- 아이를 학급 단위로 묶어 조회하고, 동의를 덮어쓰기가 아니라 이력으로 남긴다.
-- research_code 는 아이 한 명의 연구 식별자라 기관·학급 조회에 쓸 수 없다.

CREATE TABLE organizations (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(80)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 로그인 계정이 아니라 명부다. "누가 동의를 받았나"를 가리키는 데 쓴다.
CREATE TABLE educators (
    id              BIGSERIAL    PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organizations (id),
    display_name    VARCHAR(40)  NOT NULL,
    role            VARCHAR(30),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_educators_organization ON educators (organization_id);

-- 학급/차수. class_code 는 학급 코드이고 아이 개인의 research_code 와 무관하다.
CREATE TABLE cohorts (
    id              BIGSERIAL    PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organizations (id),
    name            VARCHAR(80)  NOT NULL,
    class_code      VARCHAR(40)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cohorts_class_code UNIQUE (class_code)
);

CREATE INDEX idx_cohorts_organization ON cohorts (organization_id);

-- 아이와 학급을 직접 FK 로 이으면 소속이 하나뿐이 된다.
-- 연결 테이블을 둬서 차수 이동·반 이동이 이력으로 남는다.
CREATE TABLE learner_enrollments (
    id          BIGSERIAL    PRIMARY KEY,
    learner_id  BIGINT       NOT NULL REFERENCES learners (id),
    cohort_id   BIGINT       NOT NULL REFERENCES cohorts (id),
    enrolled_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    left_at     TIMESTAMPTZ,
    CONSTRAINT uq_learner_enrollments UNIQUE (learner_id, cohort_id)
);

CREATE INDEX idx_learner_enrollments_learner ON learner_enrollments (learner_id);
CREATE INDEX idx_learner_enrollments_cohort ON learner_enrollments (cohort_id);

-- 추가만 하는 동의 장부. 철회는 행 삭제가 아니라 withdrawn_at 기록이다.
-- learners.conversation_storage_consent 는 현재 상태 캐시로 남고, 근거는 이 테이블이 갖는다.
CREATE TABLE consent_records (
    id             BIGSERIAL    PRIMARY KEY,
    learner_id     BIGINT       NOT NULL REFERENCES learners (id),
    -- conversation_storage 등. 동의 항목이 늘면 scope 로 구분한다.
    scope          VARCHAR(40)  NOT NULL,
    -- 보호자에게 보여준 동의 문서의 버전.
    policy_version VARCHAR(40)  NOT NULL,
    granted        BOOLEAN      NOT NULL,
    collected_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- 수집 주체. 연구자·교사 표식이며 아직 educators FK 로 강제하지 않는다.
    collected_by   VARCHAR(60),
    withdrawn_at   TIMESTAMPTZ
);

CREATE INDEX idx_consent_records_learner ON consent_records (learner_id, scope);

-- 기존 학습자의 현재 동의 상태를 기준선 이력으로 옮겨 둔다.
-- 이 행이 있어야 철회 요청이 왔을 때 무엇을 철회하는지 기록할 수 있다.
INSERT INTO consent_records (learner_id, scope, policy_version, granted, collected_at)
SELECT id, 'conversation_storage', 'pilot-baseline', conversation_storage_consent, created_at
FROM learners;
