-- 카페 스테이지를 몇 번이든 다시 연습할 수 있게 한다.
-- 지금까지는 (방문, 시나리오)당 대화가 하나뿐이라, 한 번 끝낸 스테이지를 다시 열면
-- 이미 completed 인 대화가 그대로 돌아와 아이가 답을 쓸 수 없었다.
-- 재연습 한 회차를 round 로 구분해 회차마다 새 대화를 만든다.
ALTER TABLE dialogue_conversations
    ADD COLUMN round      INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN cleared_at TIMESTAMPTZ;

DROP INDEX uq_dialogue_cafe_scenario;

CREATE UNIQUE INDEX uq_dialogue_cafe_scenario_round
    ON dialogue_conversations (cafe_visit_id, scenario_id, round)
    WHERE cafe_visit_id IS NOT NULL;
