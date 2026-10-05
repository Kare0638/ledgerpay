-- A webhook that reuses a stored event_id with a different body (design §9.2, #11). It is
-- acknowledged and never applied, and the original is kept; each distinct body is recorded here,
-- byte for byte, so the conflict can be investigated after the log line is gone.
CREATE TABLE inbox_conflicts (
    provider      TEXT        NOT NULL,
    event_id      TEXT        NOT NULL,
    payload_hash  TEXT        NOT NULL,
    payload       TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id, payload_hash),
    FOREIGN KEY (provider, event_id) REFERENCES inbox_events (provider, event_id)
);
