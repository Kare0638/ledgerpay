-- mock-psp's own state (design §9.4). Nothing here is copied from payment-service, so the two can
-- disagree and reconciliation has something real to find.

CREATE TABLE operations (
    psp_reference     TEXT        PRIMARY KEY,
    psp_request_id    TEXT        NOT NULL UNIQUE,   -- the caller's ID; a repeat is de-duplicated
    merchant_id       TEXT        NOT NULL,
    type              TEXT        NOT NULL CHECK (type IN ('AUTHORIZE', 'CAPTURE', 'VOID', 'REFUND')),
    parent_reference  TEXT        REFERENCES operations(psp_reference),
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency          CHAR(3)     NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_reason    TEXT,
    resource_version  INT         NOT NULL DEFAULT 1,
    settle_at         TIMESTAMPTZ NOT NULL,           -- when a PENDING operation reaches its outcome
    succeeded_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- only an authorisation stands alone; capture and void follow one, refund follows a capture
    CHECK ((type = 'AUTHORIZE') = (parent_reference IS NULL)),
    CHECK ((status = 'SUCCEEDED') = (succeeded_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);
CREATE INDEX idx_operations_due ON operations(settle_at) WHERE status = 'PENDING';
CREATE INDEX idx_operations_parent ON operations(parent_reference);
-- Backstop for the parent lock taken on submit: an authorisation is captured or voided at most
-- once. Refund totals cannot be expressed as an index, so for refunds the lock is the guard.
CREATE UNIQUE INDEX uq_operations_one_capture_or_void ON operations(parent_reference)
    WHERE type IN ('CAPTURE', 'VOID') AND status IN ('PENDING', 'SUCCEEDED');

-- Final states are immutable: a settled operation can never change its outcome.
CREATE FUNCTION operations_final_state_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'operation % is final (%) and cannot change', OLD.psp_reference, OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER operations_final_state_immutable
    BEFORE UPDATE ON operations
    FOR EACH ROW EXECUTE FUNCTION operations_final_state_immutable();

CREATE TABLE webhook_events (
    event_id         TEXT        PRIMARY KEY,
    psp_reference    TEXT        NOT NULL REFERENCES operations(psp_reference),
    payload          TEXT        NOT NULL,           -- the exact body, so every redelivery is identical
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_events_due ON webhook_events(next_attempt_at) WHERE delivered_at IS NULL;
