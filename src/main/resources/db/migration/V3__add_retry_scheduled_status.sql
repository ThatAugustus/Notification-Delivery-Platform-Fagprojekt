-- Add RETRY_SCHEDULED to the allowed status values.
-- This status means delivery failed, but we haven't used all retries yet

ALTER TABLE notifications DROP CONSTRAINT notifications_status_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_status_check
    CHECK (status IN ('ACCEPTED', 'QUEUED', 'PROCESSING', 'DELIVERED', 'FAILED', 'RETRY_SCHEDULED'));