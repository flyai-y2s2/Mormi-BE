-- #45 이전에는 놀이동산 방문 완료와 장소 완료 원장이 따로 움직였다.
-- 이미 완료된 방문이 있는 학습자의 theme_progress 완료 시각을 최초 방문 완료 시각으로 보정한다.
UPDATE theme_progress tp
SET completed_at = completed_visit.completed_at
FROM (
    SELECT learner_id, MIN(completed_at) AS completed_at
    FROM amusement_park_visits
    WHERE completed_at IS NOT NULL
    GROUP BY learner_id
) completed_visit
WHERE tp.learner_id = completed_visit.learner_id
  AND tp.theme_id = 'amusement_park'
  AND tp.completed_at IS NULL;
