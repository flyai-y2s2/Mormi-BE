-- 학습자 인증을 research_code 단독에서 login_id + password 로 옮긴다.
-- research_code 는 연구 식별자 전용으로 남고 인증에서는 분리된다.
-- 기존 파일럿 학습자는 아이디·비밀번호가 없으므로 NULL 을 허용한다.
-- NULL 은 서로 중복으로 치지 않으므로 UNIQUE 제약과 공존한다.
-- FE 전환이 끝난 뒤 후속 마이그레이션에서 NOT NULL 을 부여한다.
ALTER TABLE learners
    ADD COLUMN login_id      VARCHAR(30),
    ADD COLUMN password_hash VARCHAR(60);

ALTER TABLE learners
    ADD CONSTRAINT uq_learners_login_id UNIQUE (login_id);

-- 로그인 세션 1건이 1행. learners.token_hash 컬럼 1개로는
-- 다기기 로그인·로그아웃·만료를 표현할 수 없어 테이블로 옮긴다.
-- 폐기는 행 삭제가 아니라 revoked_at 기록으로 남겨 감사 추적을 유지한다.
CREATE TABLE learner_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    learner_id BIGINT       NOT NULL REFERENCES learners (id),
    token_hash VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_learner_tokens_token_hash UNIQUE (token_hash)
);

-- 전체 기기 로그아웃이 learner_id 로 일괄 폐기한다.
CREATE INDEX idx_learner_tokens_learner ON learner_tokens (learner_id);

-- 기존 learners.token_hash 는 이번 마이그레이션에서 제거하지 않는다.
-- FE 전환 완료 후 별도 마이그레이션에서 드롭한다.
-- 다만 토큰은 learner_tokens 가 관리하므로 신규 학습자는 이 컬럼을 채우지 않는다.
ALTER TABLE learners
    ALTER COLUMN token_hash DROP NOT NULL;
