-- Double-entry ledger (design §5.5, §6).
--
-- The invariants below are enforced by the database so they hold even if application code is wrong:
--   1. every journal has at least two postings and sums to zero, checked at commit;
--   2. at most one journal per PSP operation;
--   3. journals and postings are append-only, and a journal is closed once its transaction commits;
--   4. a posting's currency matches its account's currency.
--
-- Sign convention: postings.amount_minor is signed, debit positive and credit negative.

CREATE TABLE accounts (
    id        TEXT PRIMARY KEY,           -- e.g. 'merchant_payable:m_123'
    type      TEXT    NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE')),
    currency  CHAR(3) NOT NULL,
    UNIQUE (id, currency)                 -- target of the postings (account_id, currency) key
);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    psp_operation_id  UUID        NOT NULL UNIQUE REFERENCES psp_operations(id),
    entry_type        TEXT        NOT NULL CHECK (entry_type IN ('CAPTURE', 'REFUND')),
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      UUID    NOT NULL REFERENCES journal_entries(id),
    account_id    TEXT    NOT NULL,
    amount_minor  BIGINT  NOT NULL CHECK (amount_minor <> 0),
    currency      CHAR(3) NOT NULL,
    FOREIGN KEY (account_id, currency) REFERENCES accounts(id, currency)
);
CREATE INDEX idx_postings_account ON postings(account_id);
CREATE INDEX idx_postings_entry ON postings(entry_id);

-- Invariant 1: checked when the transaction commits, not per row, because a row-level CHECK cannot
-- see the other postings of the same journal.
CREATE FUNCTION ledger_check_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    journal_id UUID;
    posting_count INT;
    unbalanced_currencies INT;
BEGIN
    -- Separate branches: each NEW field is resolved only for the table that has it.
    IF TG_TABLE_NAME = 'journal_entries' THEN
        journal_id := NEW.id;
    ELSE
        journal_id := NEW.entry_id;
    END IF;

    SELECT count(*) INTO posting_count FROM postings WHERE entry_id = journal_id;
    IF posting_count < 2 THEN
        RAISE EXCEPTION 'journal % has % posting(s); at least 2 are required', journal_id, posting_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*) INTO unbalanced_currencies FROM (
        SELECT currency FROM postings WHERE entry_id = journal_id
        GROUP BY currency HAVING sum(amount_minor) <> 0
    ) AS unbalanced;
    IF unbalanced_currencies > 0 THEN
        RAISE EXCEPTION 'journal % is unbalanced: postings do not sum to zero', journal_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- On journal_entries: catches a journal committed with no postings at all.
CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_journal_balanced();

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_journal_balanced();

-- Invariant 3: no UPDATE, DELETE or TRUNCATE. Corrections are new, linked reversal journals.
CREATE FUNCTION ledger_reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_modification();
CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_modification();

-- Invariant 3, continued: postings may only be added in the transaction that created their journal.
-- Without this, a later transaction could append a balanced pair to a committed journal.
-- xmin is the inserting (sub)transaction's ID, so a journal created inside a SAVEPOINT is rejected
-- here. That fails closed, and the money transaction does not need savepoints (design §9.3).
CREATE FUNCTION ledger_check_journal_open() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM journal_entries
        WHERE id = NEW.entry_id AND xmin = pg_current_xact_id()::xid
    ) THEN
        RAISE EXCEPTION 'journal % is closed: postings can only be added in the transaction that created it',
            NEW.entry_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER postings_journal_open
    BEFORE INSERT ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_check_journal_open();
