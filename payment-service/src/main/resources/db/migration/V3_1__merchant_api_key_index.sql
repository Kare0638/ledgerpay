-- Merchants authenticate with a bearer API key; the service looks them up by the key's SHA-256.
-- Unique, so a hash can never identify two merchants.
CREATE UNIQUE INDEX merchants_api_key_hash_key ON merchants (api_key_hash);
