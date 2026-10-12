-- An event the broker refuses for what it is (too large, not serialisable), not because the broker
-- is down (design §10, ADR 0006). Each refusal is counted; after ledgerpay.outbox.max-rejections it
-- is quarantined: no longer claimed, and an alert for a human. Its payment's later events stay
-- behind it, so a consumer never sees them out of order; other payments are not affected.

ALTER TABLE outbox_events
    ADD COLUMN rejections     INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error     TEXT,
    ADD COLUMN quarantined_at TIMESTAMPTZ;
