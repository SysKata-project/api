#!/bin/sh
# Test-only restore against the named disposable fixture; never production.
set -eu
engine=${CONTAINER_ENGINE:-podman}
name=syskata-foundation-test-db
mkdir -p target/restore-evidence
umask 077
# Seed an account that is deleted AFTER the snapshot, plus a surviving account.
"$engine" exec -i "$name" psql -X -v ON_ERROR_STOP=1 -U syskata -d syskata <<'SQL'
INSERT INTO learners(id,issuer,subject) VALUES
 ('00000000-0000-0000-0000-000000000001','restore-test','deleted-after-snapshot'),
 ('00000000-0000-0000-0000-000000000002','restore-test','survivor')
 ON CONFLICT DO NOTHING;
SQL
# No learner data leaves the disposable container. The independent ledger is NOT restored.
"$engine" exec "$name" sh -ec '
  pg_dump -U syskata -Fc syskata > /tmp/syskata-application.dump
  pg_dump -U syskata -Fc keycloak > /tmp/syskata-keycloak.dump
  createdb -U syskata syskata_restore
  createdb -U syskata keycloak_restore
  pg_restore -U syskata -d syskata_restore --exit-on-error /tmp/syskata-application.dump
  pg_restore -U syskata -d keycloak_restore --exit-on-error /tmp/syskata-keycloak.dump
'
"$engine" exec -i "$name" psql -X -v ON_ERROR_STOP=1 -U syskata -d syskata_evidence <<'SQL'
INSERT INTO deletion_requests(id,issuer,subject)
 VALUES ('00000000-0000-0000-0000-000000000003','restore-test','deleted-after-snapshot')
 ON CONFLICT DO NOTHING;
SQL
# Reconcile deletions from the current independent ledger before access can open.
"$engine" exec -i "$name" psql -X -v ON_ERROR_STOP=1 -U syskata -d syskata_restore <<'SQL'
CREATE EXTENSION dblink;
DELETE FROM learners l USING dblink('dbname=syskata_evidence user=syskata',
 'SELECT issuer,subject FROM deletion_requests') AS d(issuer text,subject text)
WHERE l.issuer=d.issuer AND l.subject=d.subject;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM learners l JOIN dblink('dbname=syskata_evidence user=syskata',
 'SELECT issuer,subject FROM deletion_requests') AS d(issuer text,subject text)
 ON l.issuer=d.issuer AND l.subject=d.subject) THEN
 RAISE EXCEPTION 'Restore reconciliation failed'; END IF;
 IF NOT EXISTS (SELECT 1 FROM learners WHERE issuer='restore-test' AND subject='survivor') THEN
 RAISE EXCEPTION 'Active account lost'; END IF;
 IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success) THEN
 RAISE EXCEPTION 'Migration history missing'; END IF;
END $$;
DROP EXTENSION dblink;
SQL
"$engine" exec "$name" sh -ec '
  dropdb -U syskata syskata_restore
  dropdb -U syskata keycloak_restore
  rm /tmp/syskata-application.dump /tmp/syskata-keycloak.dump
'
printf '%s\n' '{"application_restore":"passed","keycloak_database_restore":"passed","deletion_reconciliation":"passed","scope":"isolated synthetic fixture"}' > target/restore-evidence/summary.json
