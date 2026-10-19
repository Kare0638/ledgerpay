-- Ledger integrity checks (design §11.2, #19): the books against themselves, independent of any PSP
-- report. Each check is kept, append-only, with what it found.

CREATE TABLE ledger_integrity_checks (
    id               UUID PRIMARY KEY,
    trigger          TEXT        NOT NULL CHECK (trigger IN ('ON_DEMAND', 'RECON_RUN')),
    recon_run_id     UUID        REFERENCES recon_runs(id),
    status           TEXT        NOT NULL CHECK (status IN ('PASSED', 'FAILED')),
    snapshot_at      TIMESTAMPTZ NOT NULL,              -- when the books were read
    journals         BIGINT      NOT NULL,              -- how many were checked
    postings         BIGINT      NOT NULL,
    violation_count  INT         NOT NULL,
    violations       JSONB,                             -- [{check, subject, detail}], the first 1,000
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'PASSED') = (violation_count = 0)),
    CHECK ((trigger = 'RECON_RUN') = (recon_run_id IS NOT NULL))
);
CREATE INDEX idx_ledger_integrity_checks_recon ON ledger_integrity_checks(recon_run_id);
CREATE INDEX idx_ledger_integrity_checks_time ON ledger_integrity_checks(checked_at DESC);

CREATE TRIGGER ledger_integrity_checks_append_only
    BEFORE UPDATE OR DELETE ON ledger_integrity_checks
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER ledger_integrity_checks_no_truncate
    BEFORE TRUNCATE ON ledger_integrity_checks
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
