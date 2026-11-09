-- This database is independent of application snapshots. Never restore an older
-- ledger over current evidence when restoring the application database.
CREATE TABLE deletion_requests (
 id uuid PRIMARY KEY,
 issuer text NOT NULL,
 subject text NOT NULL,
 accepted_at timestamptz NOT NULL DEFAULT now(),
 delete_by timestamptz NOT NULL DEFAULT now() + interval '30 days',
 completed_at timestamptz,
 identity_deleted_at timestamptz,
 labs_deleted_at timestamptz,
 UNIQUE (issuer, subject),
 CHECK (delete_by <= accepted_at + interval '30 days'),
 CHECK (completed_at IS NULL OR (identity_deleted_at IS NOT NULL AND labs_deleted_at IS NOT NULL))
);
