-- V18 backfilled the newest completion only at the time that migration ran.
-- Learners who completed a newer matching session before the durable scheduler
-- was deployed can therefore have an old sent row but no job for their latest
-- two completions. Add the current newest eligible completion once; the unique
-- trigger key and the AI idempotency key keep this replay safe.
INSERT INTO ladder_analysis_outbox (learner_id, trigger_session_id)
SELECT learner_id, public_id
FROM (
    SELECT
        learner_id,
        public_id,
        ROW_NUMBER() OVER (
            PARTITION BY learner_id, curriculum_session_id
            ORDER BY completed_at DESC, id DESC
        ) AS position_in_subunit,
        COUNT(*) OVER (
            PARTITION BY learner_id, curriculum_session_id
        ) AS completed_count
    FROM learning_sessions
    WHERE completed_at IS NOT NULL
) ranked
WHERE position_in_subunit = 1
  AND completed_count >= 2
ON CONFLICT (trigger_session_id) DO NOTHING;
