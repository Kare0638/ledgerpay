-- Reconciliation runs and their items (design §11.2, §11.3; ADR 0007). A run is bound to one VALID
-- settlement report version and one snapshot of the books. It is RUNNING until it COMPLETES or
-- FAILS, and never changes afterwards; its items can only be written while it is RUNNING.

CREATE TABLE recon_runs (
    id             UUID PRIMARY KEY,
    report_id      UUID        NOT NULL REFERENCES settlement_reports(id),
    rules_version  TEXT        NOT NULL,
    status         TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    snapshot_at    TIMESTAMPTZ,                       -- when the books were read
    summary        JSONB,                             -- counts and amounts per category
    error          TEXT,                              -- why a run FAILED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    CHECK ((status = 'COMPLETED') = (summary IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR snapshot_at IS NOT NULL),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CHECK ((status = 'FAILED') = (error IS NOT NULL))
);
CREATE INDEX idx_recon_runs_report ON recon_runs(report_id, created_at);
CREATE INDEX idx_recon_runs_running ON recon_runs(created_at) WHERE status = 'RUNNING';

-- Every external row and every internal record of a run is in exactly one item.
CREATE TABLE recon_items (
    run_id             UUID    NOT NULL REFERENCES recon_runs(id),
    item_no            INT     NOT NULL,
    category           TEXT    NOT NULL CHECK (category IN ('MATCHED', 'DUPLICATE_EXTERNAL',
                                   'MISSING_INTERNAL', 'MISSING_EXTERNAL', 'AMOUNT_MISMATCH',
                                   'STATUS_MISMATCH')),
    psp_reference      TEXT,
    psp_operation_id   UUID,
    external_line_nos  INT[]   NOT NULL DEFAULT '{}', -- lines of the report's file
    internal           JSONB,                         -- the operation as the snapshot saw it
    diff               JSONB,                         -- field-by-field differences
    PRIMARY KEY (run_id, item_no),
    CHECK (cardinality(external_line_nos) > 0 OR psp_operation_id IS NOT NULL),
    CHECK ((psp_operation_id IS NULL) = (internal IS NULL))
);
CREATE INDEX idx_recon_items_category ON recon_items(run_id, category, item_no);

CREATE FUNCTION recon_run_finishes_once() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'recon run % is % and cannot change', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.report_id <> OLD.report_id OR NEW.rules_version <> OLD.rules_version
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'recon run % can only be finished', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER recon_runs_finish_once
    BEFORE UPDATE OR DELETE ON recon_runs
    FOR EACH ROW EXECUTE FUNCTION recon_run_finishes_once();

CREATE FUNCTION recon_items_only_while_running() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- FOR SHARE: a run cannot finish while its items are still being written.
    IF NOT EXISTS (SELECT 1 FROM recon_runs WHERE id = NEW.run_id AND status = 'RUNNING' FOR SHARE) THEN
        RAISE EXCEPTION 'recon run % is not running: its items are final', NEW.run_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER recon_items_while_running
    BEFORE INSERT ON recon_items
    FOR EACH ROW EXECUTE FUNCTION recon_items_only_while_running();
CREATE TRIGGER recon_items_append_only
    BEFORE UPDATE OR DELETE ON recon_items
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER recon_runs_no_truncate
    BEFORE TRUNCATE ON recon_runs
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER recon_items_no_truncate
    BEFORE TRUNCATE ON recon_items
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
