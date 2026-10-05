-- Consecutive attempts that established nothing (#10). Unlike attempts, which every claim raises,
-- it is reset whenever the PSP is reached, so safety-net inquiries of an operation the PSP keeps
-- pending for hours never add up to needs_review.
ALTER TABLE psp_operations ADD COLUMN failures INT NOT NULL DEFAULT 0;
