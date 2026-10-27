-- Precedents for the reconciliation assistant (#64): each analysis keeps what kind of break it
-- was, as text and as an embedding, so a later analysis can be given the closest earlier ones
-- that were not overruled. Columns added to an append-only table: set when a row is inserted,
-- never after.
-- pgvector is not a trusted extension: creating it needs a superuser. Where Flyway's role is not
-- one, an administrator runs this statement first, and IF NOT EXISTS lets the migration through.
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE assistant_analyses
    ADD COLUMN break_signature TEXT,                       -- BreakSignature: no IDs, times or amounts
    ADD COLUMN break_embedding vector(768),                -- null when the embedding call failed
    ADD COLUMN precedent_ids   UUID[] NOT NULL DEFAULT '{}'; -- the analyses it was given, in order

-- No vector index: an exact scan of a few thousand analyses is milliseconds, and an approximate
-- index (HNSW) would only start to pay off well beyond what one operations team produces.
