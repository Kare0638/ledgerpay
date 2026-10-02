-- Fault injection (design §9.4): the fault chosen for an operation when it was created, so that
-- settling and webhook delivery can act on it; how many times a webhook is sent per delivery; and
-- the event that must be delivered before this one may be (OUT_OF_ORDER's stale event).
ALTER TABLE operations ADD COLUMN fault TEXT;
ALTER TABLE webhook_events ADD COLUMN copies INT NOT NULL DEFAULT 1 CHECK (copies >= 1);
ALTER TABLE webhook_events ADD COLUMN after_event_id TEXT REFERENCES webhook_events(event_id);
