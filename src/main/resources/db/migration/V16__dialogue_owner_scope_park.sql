-- 놀이동산 대화가 저장되지 못하던 문제(#42).
-- V2 의 소유자 제약은 "학습세션 아니면 카페방문" 2택이었는데, V15 에서 놀이동산을 붙일 때
-- park_visit_id 컬럼만 추가하고 이 제약은 그대로 두었다. 놀이동산 대화는 앞의 두 값이 모두
-- NULL 이라 INSERT 가 항상 위반으로 막혔다(SQLState 23514).
--
-- 소유자가 셋으로 늘었으므로 XOR 을 "NOT NULL 인 것이 정확히 하나" 로 일반화한다.
-- 기존 행은 모두 세션/카페 소유라 그대로 통과하고, 놀이동산 행은 아직 하나도 없다.
ALTER TABLE dialogue_conversations
    DROP CONSTRAINT ck_dialogue_owner_scope;

ALTER TABLE dialogue_conversations
    ADD CONSTRAINT ck_dialogue_owner_scope CHECK (
        (learning_session_id IS NOT NULL)::INT
      + (cafe_visit_id       IS NOT NULL)::INT
      + (park_visit_id       IS NOT NULL)::INT = 1
    );
