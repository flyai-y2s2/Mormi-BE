-- 계정은 남기고 진행도만 비운다. 팀원 고정 계정으로 "처음 상태" 를 다시 볼 때 쓴다.
--
-- 계정을 지우지 않는 이유:
--   지울 때마다 새로 가입하면 계정이 계속 늘어난다. 지금 배포 DB 에 쌓인
--   테스트 학습자 29건이 그 결과다. 계정은 두고 안의 기록만 비우면
--   접두사 규칙(DEV-/QA-)도 유지되고 정리 대상도 늘지 않는다.
--
-- 사용법:
--   psql "$DEV_DATABASE_URL" -v login_id="'dev-jinyoung'" -f scripts/reset-learner-progress.sql

\set ON_ERROR_STOP on

DO $$
DECLARE
    db_name text := current_database();
BEGIN
    IF db_name NOT LIKE '%\_dev' THEN
        RAISE EXCEPTION
            '개발 DB 에서만 실행할 수 있습니다. 현재 접속: %.', db_name;
    END IF;
END $$;

BEGIN;

-- 대상 학습자를 먼저 잡아 둔다. 없는 아이디를 넘기면 아무것도 지우지 않고 끝난다.
CREATE TEMP TABLE target ON COMMIT DROP AS
SELECT id FROM learners WHERE login_id = :login_id;

-- 자식부터 지운다. 여기서는 CASCADE 를 쓰지 않는다.
-- learners 행은 남겨야 하므로 삭제 범위를 눈으로 확인할 수 있게 적는다.
DELETE FROM attempts
 WHERE learning_session_id IN (
     SELECT id FROM learning_sessions WHERE learner_id IN (SELECT id FROM target));

DELETE FROM cafe_visit_stages
 WHERE cafe_visit_id IN (
     SELECT id FROM cafe_visits WHERE learner_id IN (SELECT id FROM target));

DELETE FROM dialogue_conversations WHERE learner_id IN (SELECT id FROM target);
DELETE FROM reward_ledger          WHERE learner_id IN (SELECT id FROM target);
DELETE FROM learning_sessions      WHERE learner_id IN (SELECT id FROM target);
DELETE FROM cafe_visits            WHERE learner_id IN (SELECT id FROM target);
DELETE FROM theme_progress         WHERE learner_id IN (SELECT id FROM target);

-- 토큰은 남긴다. 지우면 그 기기에서 로그아웃되어 다시 로그인해야 한다.
-- 로그아웃까지 원하면 아래 주석을 푼다.
-- DELETE FROM learner_tokens WHERE learner_id IN (SELECT id FROM target);

COMMIT;

SELECT l.id, l.login_id, l.display_name,
       (SELECT count(*) FROM learning_sessions s WHERE s.learner_id = l.id) AS sessions_left
  FROM learners l
 WHERE l.login_id = :login_id;
