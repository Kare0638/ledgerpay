-- The operational gauges (design §13, #22) count quarantined webhooks and sum reserved refunds on
-- every scrape. Both are rare rows in large tables, so each gets a partial index.
CREATE INDEX idx_inbox_quarantined ON inbox_events(received_at) WHERE status = 'QUARANTINED';
CREATE INDEX idx_payments_refund_reserved ON payments(id) WHERE refund_reserved_minor > 0;
