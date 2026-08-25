CREATE TABLE ladder_analysis_outbox (
    id                 BIGSERIAL    PRIMARY KEY,
    learner_id         BIGINT       NOT NULL REFERENCES learners (id),
    trigger_session_id VARCHAR(60)  NOT NULL REFERENCES learning_sessions (public_id),
    status             VARCHAR(20)  NOT NULL DEFAULT 'pending',
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    available_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    lease_until        TIMESTAMPTZ,
    claim_token        VARCHAR(36),
    sent_at            TIMESTAMPTZ,
    CONSTRAINT uq_ladder_analysis_outbox_trigger UNIQUE (trigger_session_id)
);

CREATE INDEX idx_ladder_analysis_outbox_claim
    ON ladder_analysis_outbox (status, available_at, lease_until, id);

-- Backfill one newest eligible completion for every learner and subunit. The
-- dispatcher reconstructs the metadata from the two newest matching sessions,
-- so no child speech is copied into the outbox.
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
