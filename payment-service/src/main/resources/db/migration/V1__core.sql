-- Core payment tables (design §6).

CREATE TABLE merchants (
    id            TEXT PRIMARY KEY,
    api_key_hash  TEXT    NOT NULL,
    fee_bps       INT     NOT NULL DEFAULT 100 CHECK (fee_bps BETWEEN 0 AND 10000),
    webhook_url   TEXT
);

CREATE TABLE payments (
    id                     UUID PRIMARY KEY,
    merchant_id            TEXT        NOT NULL REFERENCES merchants(id),
    merchant_reference     TEXT        NOT NULL,
    amount_minor           BIGINT      NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    currency               CHAR(3)     NOT NULL CHECK (currency = 'GBP'),
    status                 TEXT        NOT NULL,
    captured_minor         BIGINT      NOT NULL DEFAULT 0,
    refunded_minor         BIGINT      NOT NULL DEFAULT 0,
    refund_reserved_minor  BIGINT      NOT NULL DEFAULT 0,
    version                BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, merchant_reference),
    CHECK (refunded_minor >= 0 AND refund_reserved_minor >= 0),
    CHECK (refunded_minor + refund_reserved_minor <= captured_minor)
);

CREATE TABLE refunds (
    id                  UUID PRIMARY KEY,
    payment_id          UUID        NOT NULL REFERENCES payments(id),
    merchant_id         TEXT        NOT NULL,
    merchant_reference  TEXT        NOT NULL,
    amount_minor        BIGINT      NOT NULL CHECK (amount_minor > 0),
    status              TEXT        NOT NULL,          -- PENDING / SUCCEEDED / FAILED
    reason              TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, merchant_reference)
);

CREATE TABLE psp_operations (
    id               UUID PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payments(id),
    refund_id        UUID        REFERENCES refunds(id),
    type             TEXT        NOT NULL,             -- AUTHORIZE / CAPTURE / VOID / REFUND
    psp_request_id   TEXT        NOT NULL UNIQUE,      -- fixed across retries
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    status           TEXT        NOT NULL,             -- PENDING / SUCCEEDED / FAILED
    psp_reference    TEXT        UNIQUE,               -- PSP transaction ID
    succeeded_at     TIMESTAMPTZ,                      -- PSP success time, used by reconciliation
    resource_version INT         NOT NULL DEFAULT 0,
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until      TIMESTAMPTZ,
    last_error       TEXT,
    needs_review     BOOLEAN     NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_psp_ops_due ON psp_operations(next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE idempotency_keys (
    merchant_id     TEXT        NOT NULL,
    scope           TEXT        NOT NULL,   -- e.g. 'POST /payments'
    idem_key        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,   -- SHA-256 of the canonical request body
    status          TEXT        NOT NULL,   -- IN_PROGRESS / COMPLETED
    response_code   INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, scope, idem_key)
);
