-- Fault injection (design §9.4): the fault chosen for an operation when it was created, so that
-- settling and webhook delivery can act on it; and how many times a webhook is sent per delivery.
ALTER TABLE operations ADD COLUMN fault TEXT;
ALTER TABLE webhook_events ADD COLUMN copies INT NOT NULL DEFAULT 1 CHECK (copies >= 1);
