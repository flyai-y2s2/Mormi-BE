-- V18 could mark production choice-only drill sessions as sent before an AI job was
-- registered because those attempts do not contain expression_level. Replaying sent
-- rows is safe: the AI endpoint is idempotent and returns 409 for jobs it already has.
UPDATE ladder_analysis_outbox
SET status = 'pending',
    available_at = NOW(),
    lease_until = NULL,
    claim_token = NULL,
    sent_at = NULL
WHERE status = 'sent';
