CREATE TABLE accounts (
    id            BIGINT      NOT NULL PRIMARY KEY COMMENT '로그인 계정. 학생·교사 공용',
    login_id      VARCHAR(60) NOT NULL COMMENT 'UK. 전역 유니크. V12 이전 구 학습자는 legacy: 접두',
    password_hash VARCHAR(60) NOT NULL COMMENT 'BCrypt 해시. 구 학습자는 !disabled (로그인 불가)',
    role          VARCHAR(20) NOT NULL COMMENT 'learner, educator',
    created_at    TIMESTAMP   NOT NULL
);

CREATE TABLE auth_tokens (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT '로그인 세션 1건 = 1행. learner_tokens 대체',
    account_id BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL COMMENT 'UK. 액세스 토큰의 SHA-256 해시',
    expires_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP   COMMENT '로그아웃 시각. NULL 이면 살아 있음',
    created_at TIMESTAMP   NOT NULL,
    FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE TABLE organizations (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT '기관 PK',
    name       VARCHAR(80) NOT NULL COMMENT '기관명',
    created_at TIMESTAMP   NOT NULL
);

CREATE TABLE cohorts (
    id              BIGINT      NOT NULL PRIMARY KEY COMMENT '학급/차수 PK',
    organization_id BIGINT      NOT NULL,
    name            VARCHAR(80) NOT NULL COMMENT '학급명',
    class_code      VARCHAR(40) NOT NULL COMMENT 'UK. 학급 코드이며 아이 개인 research_code 와 무관',
    created_at      TIMESTAMP   NOT NULL,
    FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE TABLE educators (
    id              BIGINT      NOT NULL PRIMARY KEY COMMENT '교사/연구자 1명',
    organization_id BIGINT      NOT NULL,
    account_id      BIGINT      COMMENT 'UK. V12 추가. NULL 이면 로그인 없는 구 명부 행',
    display_name    VARCHAR(40) NOT NULL,
    role            VARCHAR(30) COMMENT '직위. 교사, 연구자 등. accounts.role 과 다른 값',
    created_at      TIMESTAMP   NOT NULL,
    FOREIGN KEY (organization_id) REFERENCES organizations (id),
    FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE TABLE cohort_research_codes (
    id         BIGINT      NOT NULL PRIMARY KEY COMMENT 'V13. 참여 번호 사전 발급 장부',
    cohort_id  BIGINT      NOT NULL,
    code       VARCHAR(40) NOT NULL COMMENT 'UK. 아이가 가입할 때 입력하는 참여 번호',
    issued_by  BIGINT      NOT NULL COMMENT '발급 교사. consent_records.collected_by 근거',
    created_at TIMESTAMP   NOT NULL,
    FOREIGN KEY (cohort_id) REFERENCES cohorts (id),
    FOREIGN KEY (issued_by) REFERENCES educators (id)
);

CREATE TABLE learners (
    id                           BIGINT      NOT NULL PRIMARY KEY COMMENT '아이 PK. 밖으로 노출하지 않음',
    account_id                   BIGINT      NOT NULL COMMENT 'UK. V12 추가. 로그인 정보는 accounts 가 관리',
    display_name                 VARCHAR(40) NOT NULL COMMENT '화면 표시명. 실명 아님',
    research_code                VARCHAR(40) NOT NULL COMMENT 'UK. 연구 식별자 전용이며 인증에 쓰지 않음',
    analytics_id                 CHAR(36)    NOT NULL COMMENT 'UK. Postgres UUID. 외부 분석 도구용',
    conversation_storage_consent BOOLEAN     NOT NULL COMMENT '현재 동의 상태 캐시. 근거는 consent_records',
    retention_policy             VARCHAR(20) NOT NULL COMMENT 'permanent 기본값. V3 에서 변경',
    onboarding_completed_at      TIMESTAMP,
    created_at                   TIMESTAMP   NOT NULL,
    FOREIGN KEY (account_id) REFERENCES accounts (id)
);

CREATE TABLE learner_enrollments (
    id          BIGINT    NOT NULL PRIMARY KEY COMMENT '아이와 학급의 연결. 반 이동을 이력으로 남김',
    learner_id  BIGINT    NOT NULL,
    cohort_id   BIGINT    NOT NULL,
    enrolled_at TIMESTAMP NOT NULL,
    left_at     TIMESTAMP COMMENT 'NULL 이면 현재 소속중',
    FOREIGN KEY (learner_id) REFERENCES learners (id),
    FOREIGN KEY (cohort_id) REFERENCES cohorts (id)
);

CREATE TABLE consent_records (
    id             BIGINT      NOT NULL PRIMARY KEY COMMENT '추가만 하는 동의 장부',
    learner_id     BIGINT      NOT NULL,
    scope          VARCHAR(40) NOT NULL COMMENT 'conversation_storage 등 동의 항목',
    policy_version VARCHAR(40) NOT NULL COMMENT '보호자에게 보여준 동의 문서 버전',
    granted        BOOLEAN     NOT NULL,
    collected_at   TIMESTAMP   NOT NULL,
    collected_by   VARCHAR(60) COMMENT '수집 주체 표식. 참여 번호 발급 교사 이름이 들어가며 FK 로 강제하지 않음',
    withdrawn_at   TIMESTAMP   COMMENT '철회 시각. 행 삭제 대신 이 값을 채움',
    FOREIGN KEY (learner_id) REFERENCES learners (id)
);

CREATE TABLE learning_sessions (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    public_id             VARCHAR(60)  NOT NULL COMMENT 'UK. 밖으로 나가는 유일한 식별자',
    learner_id            BIGINT       NOT NULL,
    curriculum_session_id VARCHAR(60)  NOT NULL COMMENT '커리큘럼 세션 종류',
    variant_seed          INT          NOT NULL COMMENT '아이가 본 문제를 재구성하기 위한 시드',
    scaffold_level        INT,
    elapsed_seconds       INT,
    transfer_solved       BOOLEAN      NOT NULL COMMENT '전이 문제 해결 여부',
    timed_out             BOOLEAN      NOT NULL,
    conversation_id       VARCHAR(100) COMMENT 'Mormi-AI 대화 참조. FK 아님',
    practice_result_id    VARCHAR(100),
    started_at            TIMESTAMP    NOT NULL,
    completed_at          TIMESTAMP,
    FOREIGN KEY (learner_id) REFERENCES learners (id)
);

CREATE TABLE attempts (
    id                  BIGINT       NOT NULL PRIMARY KEY COMMENT '문제 1개에 대한 시도 1건',
    learning_session_id BIGINT       NOT NULL,
    activity            VARCHAR(20)  NOT NULL,
    attempt_no          INT          NOT NULL COMMENT 'UK 조합: session + activity + attempt_no',
    item_id             VARCHAR(120) COMMENT 'learning_task_outcomes.task_key 와 같은 값',
    question_index      INT,
    is_correct          BOOLEAN      NOT NULL,
    elapsed_ms          INT,
    reward_granted      INT          NOT NULL,
    answer_meta         JSON         NOT NULL COMMENT '오답 상세를 구조 데이터로만 저장. 원문 없음',
    application_scope   VARCHAR(30)  COMMENT 'V8 추가. same_form_new_number, new_representation, real_life_context',
    support_level       INT          COMMENT 'V8 추가. FE 사다리 0 없음 ~ 3 최대 지원',
    created_at          TIMESTAMP    NOT NULL,
    FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (id)
);

CREATE TABLE theme_progress (
    id           BIGINT      NOT NULL PRIMARY KEY,
    learner_id   BIGINT      NOT NULL,
    theme_id     VARCHAR(40) NOT NULL COMMENT 'UK 조합: learner_id + theme_id',
    unlocked_at  TIMESTAMP,
    completed_at TIMESTAMP,
    FOREIGN KEY (learner_id) REFERENCES learners (id)
);

CREATE TABLE cafe_visits (
    id            BIGINT      NOT NULL PRIMARY KEY COMMENT '카페 시뮬레이션 방문 1회',
    public_id     VARCHAR(60) NOT NULL COMMENT 'UK',
    learner_id    BIGINT      NOT NULL,
    stage         VARCHAR(20) NOT NULL COMMENT 'queue, menu, calculate, change, complete',
    target_amount INT         NOT NULL,
    order_total   INT,
    paid_amount   INT,
    change_amount INT,
    started_at    TIMESTAMP   NOT NULL,
    completed_at  TIMESTAMP,
    FOREIGN KEY (learner_id) REFERENCES learners (id)
);

CREATE TABLE cafe_visit_stages (
    id            BIGINT      NOT NULL PRIMARY KEY COMMENT '카페 스테이지별 시도 기록',
    cafe_visit_id BIGINT      NOT NULL,
    stage         VARCHAR(20) NOT NULL,
    attempt_no    INT         NOT NULL COMMENT 'UK 조합: visit + stage + attempt_no',
    is_correct    BOOLEAN     NOT NULL,
    elapsed_ms    INT,
    payload       JSON        NOT NULL COMMENT '화폐별 개수, 선택 메뉴 id 같은 구조 데이터만',
    created_at    TIMESTAMP   NOT NULL,
    FOREIGN KEY (cafe_visit_id) REFERENCES cafe_visits (id)
);

CREATE TABLE reward_ledger (
    id                  BIGINT       NOT NULL PRIMARY KEY COMMENT '보상 원장. 잔액 컬럼 없이 합계로 도출',
    learner_id          BIGINT       NOT NULL,
    learning_session_id BIGINT       COMMENT 'nullable. 세션과 무관한 지급도 있음',
    source              VARCHAR(30)  NOT NULL,
    amount              INT          NOT NULL,
    idempotency_key     VARCHAR(160) NOT NULL COMMENT 'UK. 중복 지급 방지',
    created_at          TIMESTAMP    NOT NULL,
    FOREIGN KEY (learner_id) REFERENCES learners (id),
    FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (id)
);

CREATE TABLE dialogue_conversations (
    id                  BIGINT       NOT NULL PRIMARY KEY COMMENT 'AI 대화의 소유권을 BE 가 보관',
    conversation_id     VARCHAR(100) NOT NULL COMMENT 'UK. Mormi-AI 쪽 대화 id',
    learner_id          BIGINT       NOT NULL,
    learning_session_id BIGINT       COMMENT 'CHECK: 이 컬럼과 cafe_visit_id 중 정확히 하나만 채움',
    cafe_visit_id       BIGINT,
    scenario_id         VARCHAR(100) NOT NULL,
    scenario_context    JSON         NOT NULL,
    round               INT          NOT NULL COMMENT 'V4 추가. 스테이지 재연습 회차',
    cleared_at          TIMESTAMP    COMMENT 'V4 추가',
    created_at          TIMESTAMP    NOT NULL,
    FOREIGN KEY (learner_id) REFERENCES learners (id),
    FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (id),
    FOREIGN KEY (cafe_visit_id) REFERENCES cafe_visits (id)
);

CREATE TABLE observation_events (
    id             BIGINT       NOT NULL PRIMARY KEY COMMENT 'AI 이벤트를 그대로 받는 수신함',
    event_id       VARCHAR(100) NOT NULL COMMENT 'UK. 멱등성의 최종 방어선',
    schema_version VARCHAR(20)  NOT NULL,
    event_type     VARCHAR(40)  NOT NULL,
    payload        JSON         NOT NULL COMMENT '파싱 실패해도 원본 보관',
    status         VARCHAR(20)  NOT NULL COMMENT 'received, processed, failed',
    error_message  TEXT,
    received_at    TIMESTAMP    NOT NULL,
    processed_at   TIMESTAMP
);

CREATE TABLE learning_observations (
    id                     BIGINT       NOT NULL PRIMARY KEY COMMENT '파싱 성공한 이벤트의 구조화 관찰값',
    observation_event_id   BIGINT       NOT NULL,
    ai_observation_id      VARCHAR(100) NOT NULL COMMENT 'UK. AI 저장소 원본 추적용',
    ai_observation_version VARCHAR(20),
    learner_id             BIGINT       NOT NULL,
    learning_session_id    BIGINT,
    cafe_visit_id          BIGINT,
    conversation_id        VARCHAR(100) COMMENT 'FK 아님. AI 쪽 참조값',
    scenario_id            VARCHAR(100),
    task_id                VARCHAR(120) COMMENT 'attempts.item_id 와 같은 값',
    stage                  VARCHAR(40),
    turn_index             INT,
    response_category      VARCHAR(40),
    difficulty_class       VARCHAR(40),
    concept_result         VARCHAR(40),
    bottleneck_candidate   VARCHAR(60),
    expression_before      VARCHAR(4)   COMMENT '발화사다리 L4 독립 ~ L0 최대 지원',
    expression_after       VARCHAR(4),
    hint_before            VARCHAR(4)   COMMENT '힌트사다리 H0 없음 ~ H3 최대',
    hint_after             VARCHAR(4),
    transition_reason      VARCHAR(60),
    help_used              BOOLEAN      COMMENT 'NULL 은 수집 안 됨이며 false 와 뜻이 다름',
    fallback_occurred      BOOLEAN,
    system_error           BOOLEAN      COMMENT '시스템 실패는 아동 수행 실패와 분리 집계',
    completion_outcome     VARCHAR(30)  COMMENT 'independent, supported, joint, system_failure',
    classifier_confidence  DECIMAL(4,3),
    evidence_strength      VARCHAR(20),
    observed_at            TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL,
    FOREIGN KEY (observation_event_id) REFERENCES observation_events (id),
    FOREIGN KEY (learner_id) REFERENCES learners (id),
    FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (id),
    FOREIGN KEY (cafe_visit_id) REFERENCES cafe_visits (id)
);

CREATE TABLE learning_task_outcomes (
    id                        BIGINT       NOT NULL PRIMARY KEY COMMENT '문제 1개 단위 집계. attempts + observations 파생',
    learner_id                BIGINT       NOT NULL,
    learning_session_id       BIGINT       NOT NULL,
    task_key                  VARCHAR(120) NOT NULL COMMENT 'UK 조합: session + task_key. attempts.item_id 와 같은 값',
    activity                  VARCHAR(20),
    attempt_count             INT,
    wrong_attempt_count       INT,
    first_try_success         BOOLEAN,
    retry_success             BOOLEAN,
    success_after_help        BOOLEAN      COMMENT '관찰이 없으면 NULL',
    expression_start          VARCHAR(4),
    expression_end            VARCHAR(4),
    expression_lowest         VARCHAR(4)   COMMENT '지원이 최대로 필요했던 순간',
    hint_start                VARCHAR(4),
    hint_end                  VARCHAR(4),
    hint_max                  VARCHAR(4),
    completion_outcome        VARCHAR(30),
    system_failure            BOOLEAN,
    bottleneck_candidate      VARCHAR(60),
    bottleneck_evidence_count INT          COMMENT '1 이면 한 번만 관찰된 것. 오개념으로 단정 금지',
    star_note_id              VARCHAR(100) COMMENT '별노트는 Mormi-AI 보유. FK 없이 참조값만',
    star_note_attribution     VARCHAR(20),
    star_note_evidence        VARCHAR(40),
    source_attempt_ids        TEXT         NOT NULL COMMENT 'Postgres BIGINT 배열. 근거 attempts id 목록',
    source_observation_ids    TEXT         NOT NULL COMMENT 'Postgres BIGINT 배열. 근거 observations id 목록',
    aggregation_rule_version  VARCHAR(20)  NOT NULL COMMENT '집계 규칙이 바뀌면 재계산 대상 탐색용',
    computed_at               TIMESTAMP    NOT NULL,
    FOREIGN KEY (learner_id) REFERENCES learners (id),
    FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (id)
);

CREATE TABLE report_snapshots (
    id                       BIGINT      NOT NULL PRIMARY KEY COMMENT '리포트 1회 생성분을 근거와 함께 고정',
    learner_id               BIGINT      COMMENT 'CHECK: 이 컬럼과 cohort_id 중 정확히 하나만 채움',
    cohort_id                BIGINT,
    period_start             TIMESTAMP   NOT NULL,
    period_end               TIMESTAMP   NOT NULL,
    body                     JSON        NOT NULL COMMENT 'LLM 없이 성립하는 구조화 리포트 본문',
    source_observation_ids   TEXT        NOT NULL COMMENT 'Postgres BIGINT 배열',
    source_attempt_ids       TEXT        NOT NULL COMMENT 'Postgres BIGINT 배열',
    source_outcome_ids       TEXT        NOT NULL COMMENT 'Postgres BIGINT 배열',
    aggregation_rule_version VARCHAR(20) NOT NULL,
    llm_model                VARCHAR(60) COMMENT 'NULL 이면 LLM 없이 만든 스냅샷',
    llm_prompt_version       VARCHAR(40),
    generated_text           TEXT,
    teacher_edited_text      TEXT,
    approval_status          VARCHAR(20) NOT NULL COMMENT 'draft, edited, approved',
    created_at               TIMESTAMP   NOT NULL,
    FOREIGN KEY (learner_id) REFERENCES learners (id),
    FOREIGN KEY (cohort_id) REFERENCES cohorts (id)
);
