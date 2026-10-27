-- The reconciliation assistant (design §16, #26). An LLM drafts an analysis of one break from
-- read-only facts; its only action is to propose an inquiry, which a person confirms or rejects.
-- Analyses are append-only; a proposal is decided once.

CREATE TABLE assistant_analyses (
    id             UUID PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES recon_runs(id),
    item_no        INT         NOT NULL,
    model          TEXT        NOT NULL,
    status         TEXT        NOT NULL CHECK (status IN ('COMPLETED', 'INVALID_OUTPUT', 'FAILED')),
    analysis       JSONB,                 -- the validated output; null unless COMPLETED
    error          TEXT,                  -- why it is not COMPLETED
    tool_calls     JSONB       NOT NULL,  -- [{tool, args, ok}] in order, for audit
    turns          INT         NOT NULL,
    tokens         INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (run_id, item_no) REFERENCES recon_items(run_id, item_no),
    CHECK ((status = 'COMPLETED') = (analysis IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (error IS NULL))
);
CREATE INDEX idx_assistant_analyses_item ON assistant_analyses(run_id, item_no, created_at);

CREATE TABLE inquiry_proposals (
    id                UUID PRIMARY KEY,
    analysis_id       UUID        NOT NULL UNIQUE REFERENCES assistant_analyses(id),
    psp_operation_id  UUID        NOT NULL REFERENCES psp_operations(id),
    reason            TEXT        NOT NULL,
    status            TEXT        NOT NULL CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED')),
    decided_by        TEXT,                -- who confirmed or rejected, as they gave it
    decided_at        TIMESTAMPTZ,
    outcome           TEXT,                -- the inquiry request's result once confirmed
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'PROPOSED') = (decided_at IS NULL))
);

CREATE TRIGGER assistant_analyses_append_only
    BEFORE UPDATE OR DELETE ON assistant_analyses
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

-- A proposal is decided once, and only its decision fields change.
CREATE FUNCTION inquiry_proposal_decided_once() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'inquiry proposal % is % and cannot change', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.analysis_id <> OLD.analysis_id
       OR NEW.psp_operation_id <> OLD.psp_operation_id OR NEW.reason <> OLD.reason
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'inquiry proposal % can only be decided', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER inquiry_proposals_decided_once
    BEFORE UPDATE OR DELETE ON inquiry_proposals
    FOR EACH ROW EXECUTE FUNCTION inquiry_proposal_decided_once();

-- The assistant's operation timeline finds the webhooks received for an operation by request ID.
CREATE INDEX idx_inbox_events_request ON inbox_events ((payload ->> 'psp_request_id'));
