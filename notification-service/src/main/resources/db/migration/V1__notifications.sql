-- notification-service's own database (design §6, §10). It never holds money.

-- Where and how each merchant's webhooks are sent. The secret is kept in the clear because
-- signing needs it; a production system would keep it in a secrets manager (design §10).
CREATE TABLE merchant_endpoints (
    merchant_id     TEXT PRIMARY KEY,
    webhook_url     TEXT        NOT NULL,
    signing_secret  TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per event handled, written with its delivery: a redelivered event_id is skipped.
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_deliveries (
    event_id         UUID PRIMARY KEY REFERENCES processed_events(event_id),
    merchant_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    payment_id       UUID        NOT NULL,
    status           TEXT        NOT NULL,   -- DELIVERED / NO_ENDPOINT
    webhook_url      TEXT,
    response_status  INT,
    delivered_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_deliveries_merchant ON webhook_deliveries(merchant_id, delivered_at);
