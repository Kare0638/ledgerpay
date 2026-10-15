-- PSP settlement reports (design §11.1). Every upload of a new file is kept as a version, valid or
-- not, and nothing here is ever changed afterwards: a reconciliation run is bound to one version,
-- and a corrected file is a new one. The reconciliation tables follow in V9 (#18).

CREATE TABLE settlement_reports (
    id             UUID PRIMARY KEY,
    psp            TEXT        NOT NULL,
    business_date  DATE        NOT NULL,
    file_sha256    TEXT        NOT NULL,
    version        INT         NOT NULL CHECK (version >= 1),
    status         TEXT        NOT NULL CHECK (status IN ('VALID', 'INVALID')),
    row_count      INT         NOT NULL,              -- data rows in the file, valid or not
    error_count    INT         NOT NULL DEFAULT 0,
    errors         JSONB,                             -- [{line, column, message}], the first 1,000
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (psp, business_date, file_sha256),
    UNIQUE (psp, business_date, version),
    CHECK ((status = 'VALID') = (error_count = 0)),
    CHECK ((status = 'VALID') = (errors IS NULL))
);

-- The rows of a VALID report only: an INVALID one is never reconciled, so its rows are not kept.
CREATE TABLE settlement_rows (
    report_id       UUID        NOT NULL REFERENCES settlement_reports(id),
    line_no         INT         NOT NULL,             -- the file's line, for tracing a break back
    psp_reference   TEXT        NOT NULL,
    psp_request_id  TEXT        NOT NULL,
    txn_type        TEXT        NOT NULL CHECK (txn_type IN ('CAPTURE', 'REFUND')),
    merchant_id     TEXT        NOT NULL,
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3)     NOT NULL,
    succeeded_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (report_id, line_no)
);
CREATE INDEX idx_settlement_rows_reference ON settlement_rows(psp_reference);

CREATE TRIGGER settlement_reports_append_only
    BEFORE UPDATE OR DELETE ON settlement_reports
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();

CREATE TRIGGER settlement_rows_append_only
    BEFORE UPDATE OR DELETE ON settlement_rows
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER settlement_reports_no_truncate
    BEFORE TRUNCATE ON settlement_reports
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER settlement_rows_no_truncate
    BEFORE TRUNCATE ON settlement_rows
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
