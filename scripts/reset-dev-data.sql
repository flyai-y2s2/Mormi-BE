-- 개발 DB의 학습 데이터를 전부 비운다. 스키마는 건드리지 않는다.
--
-- 왜 Flyway 마이그레이션이 아니라 별도 스크립트인가:
--   Flyway 는 스키마 형상 관리용이고 모든 환경에서 자동으로 돈다.
--   데이터 삭제를 거기 넣으면 운영 DB 배포 때도 실행된다.
--
-- 왜 DELETE 가 아니라 TRUNCATE CASCADE 인가:
--   learners 를 참조하는 테이블이 8개인데 FK 에 ON DELETE CASCADE 가 없다.
--   DELETE 로 지우려면 역순으로 9번을 밟아야 하고, 순서를 한 번 틀리면
--   제약 위반으로 중간에 멈춘다. TRUNCATE ... CASCADE 는 한 번에 끝난다.
--
-- 사용법:
--   psql "$DEV_DATABASE_URL" -f scripts/reset-dev-data.sql
--
-- 주의: 되돌릴 수 없다. 아래 안전장치가 개발 DB 여부를 먼저 확인한다.

\set ON_ERROR_STOP on

DO $$
DECLARE
    db_name text := current_database();
BEGIN
    -- 운영 DB 에서 실행되면 여기서 멈춘다. 이름 규칙이 유일한 방어선이므로
    -- 개발 DB 는 반드시 _dev 로 끝나야 한다.
    IF db_name NOT LIKE '%\_dev' THEN
        RAISE EXCEPTION
            '개발 DB 에서만 실행할 수 있습니다. 현재 접속: %. 이름이 _dev 로 끝나야 합니다.',
            db_name;
    END IF;
END $$;

BEGIN;

-- flyway_schema_history 는 스키마 형상 기록이므로 남긴다.
-- 지우면 다음 배포에서 마이그레이션이 처음부터 다시 돈다.
TRUNCATE TABLE
    attempts,
    cafe_visit_stages,
    cafe_visits,
    dialogue_conversations,
    learner_tokens,
    learning_sessions,
    reward_ledger,
    theme_progress,
    learners
RESTART IDENTITY CASCADE;

COMMIT;

-- id 를 1부터 다시 시작하므로, 다음 검증에서 "id 가 33이던데?" 같은
-- 혼란이 생기지 않는다.
SELECT
    (SELECT count(*) FROM learners)            AS learners,
    (SELECT count(*) FROM learning_sessions)   AS learning_sessions,
    (SELECT count(*) FROM attempts)            AS attempts,
    (SELECT count(*) FROM dialogue_conversations) AS dialogue_conversations;
