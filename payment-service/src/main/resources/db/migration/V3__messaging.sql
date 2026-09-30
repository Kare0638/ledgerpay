-- Webhook inbox and transactional outbox (design §6, §9.2, §10).

CREATE TABLE inbox_events (
    provider      TEXT        NOT NULL,
    event_id      TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    payload_hash  TEXT        NOT NULL,
    status        TEXT        NOT NULL,   -- RECEIVED / PROCESSED / QUARANTINED
    error         TEXT,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,         -- also the message eventId
    aggregate_id  UUID        NOT NULL,     -- paymentId, used as the Kafka key
    event_type    TEXT        NOT NULL,     -- PaymentAuthorized / PaymentCaptured / RefundSucceeded …
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;
