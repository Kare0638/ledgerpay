-- The daily settlement report (design §11.1) reads successful captures and refunds by success time.
CREATE INDEX idx_operations_settlement ON operations(succeeded_at)
    WHERE status = 'SUCCEEDED' AND type IN ('CAPTURE', 'REFUND');
