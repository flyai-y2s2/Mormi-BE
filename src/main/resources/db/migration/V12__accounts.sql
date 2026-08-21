-- 학생과 교사의 로그인을 하나의 계정 체계로 합친다.
-- 테이블별 계정(learners.login_id + educators.login_id)으로 가면 서로 다른
-- 테이블의 UNIQUE 라 같은 아이디가 양쪽에 존재할 수 있고, 통합 로그인이
-- "먼저 조회한 쪽이 이긴다"가 된다. accounts 로 뽑으면 DB 제약으로
-- 전역 유니크가 되어 경합으로도 겹치지 않는다.
-- login_id 60자: 구 학습자용 'legacy:' + research_code(40자)를 담기 위해
-- learners.login_id(30자)보다 넓다. 신규 가입은 서비스에서 30자로 제한한다.
CREATE TABLE accounts (
    id            BIGSERIAL    PRIMARY KEY,
    login_id      VARCHAR(60)  NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_accounts_login_id UNIQUE (login_id),
    CONSTRAINT ck_accounts_role CHECK (role IN ('learner', 'educator'))
);

-- 아이디·비밀번호가 있는 학습자는 값을 그대로 계정으로 이관한다.
INSERT INTO accounts (login_id, password_hash, role, created_at)
SELECT login_id, password_hash, 'learner', created_at
FROM learners
WHERE login_id IS NOT NULL;

-- 구 방식(연구 코드 온보딩) 학습자는 옮길 로그인 정보가 없다.
-- 행과 학습 기록은 연구 산출물이라 지우지 않되, 접근은 포기한다:
-- '!disabled' 는 BCrypt 형식이 아니라 어떤 비밀번호와도 매칭되지 않는다.
-- 계정을 만들어 두면 learners.account_id 에 NOT NULL 을 걸 수 있어
-- 인증 코드에서 "계정이 없을 수도 있다" 분기가 사라진다.
INSERT INTO accounts (login_id, password_hash, role, created_at)
SELECT 'legacy:' || research_code, '!disabled', 'learner', created_at
FROM learners
WHERE login_id IS NULL;

ALTER TABLE learners
    ADD COLUMN account_id BIGINT REFERENCES accounts (id);

UPDATE learners l
SET account_id = a.id
FROM accounts a
WHERE l.login_id IS NOT NULL
  AND a.login_id = l.login_id;

UPDATE learners l
SET account_id = a.id
FROM accounts a
WHERE l.login_id IS NULL
  AND a.login_id = 'legacy:' || l.research_code;

-- 계정 하나가 학습자 정확히 한 명을 가리킨다.
ALTER TABLE learners
    ALTER COLUMN account_id SET NOT NULL,
    ADD CONSTRAINT uq_learners_account_id UNIQUE (account_id);

-- 로그인 정보는 accounts 가 관리한다. 컬럼을 드롭하면 UNIQUE 제약도 함께 사라진다.
-- token_hash 는 V5 가 learner_tokens 로 옮기며 드롭을 미뤄둔 것을 이번에 정리한다.
ALTER TABLE learners
    DROP COLUMN login_id,
    DROP COLUMN password_hash,
    DROP COLUMN token_hash;

-- V9 의 educators 는 명부였다. 계정이 연결되면 로그인 주체가 된다.
-- 기존 명부 행은 계정이 없을 수 있어 NULL 을 허용한다.
ALTER TABLE educators
    ADD COLUMN account_id BIGINT REFERENCES accounts (id),
    ADD CONSTRAINT uq_educators_account_id UNIQUE (account_id);

-- learner_tokens 를 역할 무관 토큰 저장소로 대체한다.
-- 구조는 learner_tokens 와 같고 소유자만 learner_id -> account_id 로 바뀐다.
CREATE TABLE auth_tokens (
    id         BIGSERIAL    PRIMARY KEY,
    account_id BIGINT       NOT NULL REFERENCES accounts (id),
    token_hash VARCHAR(64)  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_auth_tokens_token_hash UNIQUE (token_hash)
);

-- 전체 기기 로그아웃이 account_id 로 일괄 폐기한다.
CREATE INDEX idx_auth_tokens_account ON auth_tokens (account_id);

-- token_hash 값을 그대로 복사해 지금 로그인돼 있는 세션이 끊기지 않게 한다.
INSERT INTO auth_tokens (account_id, token_hash, expires_at, revoked_at, created_at)
SELECT l.account_id, t.token_hash, t.expires_at, t.revoked_at, t.created_at
FROM learner_tokens t
JOIN learners l ON l.id = t.learner_id;

DROP TABLE learner_tokens;
