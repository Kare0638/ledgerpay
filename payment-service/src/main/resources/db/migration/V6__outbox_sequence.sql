-- Outbox relay order (design §10, ADR 0006). created_at is the transaction's start time, so a
-- transaction that waited for a payment lock can carry an earlier time than the one it waited for;
-- the sequence is taken at insert, after the lock, so it follows the order of the business changes.

ALTER TABLE outbox_events ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

DROP INDEX idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox_events(seq) WHERE published_at IS NULL;
-- "Has this payment an earlier unpublished event?" for every candidate the relay looks at.
CREATE INDEX idx_outbox_unpublished_aggregate ON outbox_events(aggregate_id, seq)
    WHERE published_at IS NULL;
