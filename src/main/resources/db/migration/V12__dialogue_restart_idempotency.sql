-- 새로고침 시 스테이지를 새 회차로 재시작한다(#22).
-- 1) FE가 요청마다 새로 뽑는 멱등키 request_id 를 저장해, 네트워크 재시도로
--    같은 시작 요청이 중복 도착해도 회차가 여러 개 생기지 않게 한다.
-- 2) 홈 가르치기도 카페처럼 재시작 회차를 가질 수 있게
--    학습 세션당 대화 1개 제약을 (세션, 회차)당 1개로 바꾼다.
ALTER TABLE dialogue_conversations
    ADD COLUMN request_id VARCHAR(100);

CREATE UNIQUE INDEX uq_dialogue_request
    ON dialogue_conversations (learner_id, request_id)
    WHERE request_id IS NOT NULL;

DROP INDEX uq_dialogue_learning_session;

CREATE UNIQUE INDEX uq_dialogue_learning_session_round
    ON dialogue_conversations (learning_session_id, round)
    WHERE learning_session_id IS NOT NULL;
