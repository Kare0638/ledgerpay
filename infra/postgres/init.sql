-- Each service owns its database. mock-psp is kept separate on purpose so that its
-- settlement reports come from its own records, never from our ledger.
CREATE DATABASE ledgerpay;
CREATE DATABASE mockpsp;
