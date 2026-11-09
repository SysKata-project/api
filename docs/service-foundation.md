# Authenticated service foundation

Tracking: [api #2](https://github.com/SysKata-project/api/issues/2).
This introduces the runtime boundary and database, not catalog selection, grading,
review, or lab orchestration. Those remain in api #3–#6. The only implemented
learner operation is `POST /v1/me/account-deletion`; test-only ownership probes
are compiled from test sources and are absent from the packaged application.

## Local development

Use the pinned Java/Maven toolchain in [development.md](development.md).
PostgreSQL **17.11** and Keycloak **26.8.2** are pinned in `dev/compose.yaml`.
Quarkus extensions and Flyway/JDBC versions come from the existing pinned BOM.

1. Copy `.env.example` to ignored `.env`, restrict it with `chmod 600 .env`, and
   replace every placeholder with local values. Never commit the populated file.
2. Start the persistent local services:
   `docker compose --env-file .env -f dev/compose.yaml up -d`.
   Podman with a Compose provider may run the same file.
3. Run `./mvnw quarkus:dev` from the repository root. Quarkus reads `.env`.
   The API listens on port 8080; the identity issuer is
   `http://localhost:8180/realms/syskata`. HTTP is loopback development only.
4. Create a local learner using Keycloak's admin console; sign in using the
   public `syskata-mobile` client with Code/PKCE S256. The exact loopback redirect
   `http://localhost:8765/callback` is a test callback, not a mobile deployment URI.
   Mobile callback registration remains app #3/deployment work. No wildcard,
   implicit grant, mobile password grant or embedded client secret is enabled.
5. `docker compose --env-file .env -f dev/compose.yaml down` stops services but
   retains the database volume. **`down -v` deletes all local data**; use only for
   disposable data with explicit intent. Initializer passwords apply only on a
   fresh volume; changing `.env` alone does not rotate existing database roles.

The app, evidence and Keycloak have separate databases and users. The bootstrap
PostgreSQL administrator and Keycloak administrator are local operators, never
API runtime credentials. The app migrates its own database at startup; the
separate evidence datasource migrates its ledger. Production migration ownership
and deployment orchestration remain deployment work. Automatic Flyway clean is
disabled. Readiness includes both databases; liveness is `/q/health/live` and
readiness is `/q/health/ready`. A failed migration prevents startup.

## Authentication and request boundary

OIDC validates the exact issuer, signature, audience `syskata-api`, expiry,
not-before, and Keycloak access-token type `Bearer`. ID tokens and opaque tokens
are not accepted. The API uses `(iss, sub)`, never email. The public-key fixture
is test-only; normal runtime discovers the configured Keycloak's keys.

Every matched `/v1/` resource checks the independent deletion ledger and account
status before accessing learner data. Ledger/database failure fails closed.
`Accounts.requireSessionOwner` uses both resource ID and authenticated learner ID;
unknown and foreign sessions produce the same 404. Future nested-resource
adapters must retain those composite ownership bindings and repeat checks inside
their mutation transaction, including replay paths. This issue does not implement
those future session endpoints or the separate internal worker audience.

API request bodies are bounded to **64 KiB** by the application handler; a
separate 1 MiB HTTP transport ceiling can reject extreme requests before JSON
error handling. Clients must handle an empty transport-level 413 as well as the
normal API error envelope. Deletion accepts only the exact confirmation
object and a UUID idempotency header. Errors use static messages, generated
correlation IDs and no raw exception details. Access logging is disabled. The OIDC
logger is disabled because its rejection diagnostics can include submitted token
text. Do not enable verbose OIDC/JDBC or request-body logging with real data.
Operational log retention is at most 30 days under the accepted lifecycle policy.

## Persistence invariants

Flyway `V1__learning_foundation.sql` creates learners, immutable release metadata,
enrollments, learning sessions, exercise instances, attempts, skill progress,
review schedules, rewards, labs, assessments, outbox jobs and idempotency records.
Composite foreign keys prevent attaching another learner's instance/attempt/lab
into a session. Partial indexes enforce one active session, open attempt, live lab
and pending assessment, plus completion and local-day review reward uniqueness.
Safe-integer revisions, positive generations, fixed reward amounts and explicit
state sets constrain writes. Application adapters still own legal transitions,
content activation and transaction/replay algorithms;
schema constraints are not an implementation of those features.

Learner-owned rows cascade on deletion; release metadata survives. No learner
answers, tokens or production fixtures belong in Git. Existing migrations are
append-only after release. Apply changes as new migrations, test upgrade and
restore, and never edit checksums to hide drift.

## Deletion and independent evidence

An accepted request commits a minimal record to **a separate database** before
returning 202. Its original acceptance/deadline survives retries. All subsequent
API calls return `403 account_disabled`, including deletion retries and still-valid
tokens. A lost 202 does not mean deletion failed. The independent ledger is also
the retryable job queue; restoring the application snapshot cannot erase denial.
A ledger outage denies access rather than resurrecting an account.

Install `requirements-operations.txt` in `.venv-operations`, supply the ignored
operator DSNs and a short-lived Keycloak admin token, then run:

```sh
.venv-operations/bin/python scripts/process-deletions.py
```

The worker locks pending jobs, verifies the configured issuer before sending an
admin credential, deletes the Keycloak user (including identity sessions), deletes
application rows, and only then marks completion. HTTP 404 is an idempotent
identity-deletion success. Timeouts/failures preserve the original deadline and
deny ledger. A crash between stores is safe to retry; no cross-database atomicity
is claimed. The operator must run/monitor jobs often enough to revoke sessions
promptly and meet the **30-day active-data deadline**; an expired deadline never
becomes permission to ignore a pending job.

Until labs #2/#5 supplies a cleanup adapter, any recorded lab causes the worker
to stop without deleting tracking data or declaring cleanup complete. No lab
service exists in this foundation. Integrating termination/revocation with actual
lab/gateway services is mandatory before those features are exposed.

Keep pending evidence until completion is verified. Keep completed evidence until
at least 30 days afterward and until no backup/restore can reintroduce the account.
Review recovery-risk extensions; do not automatically purge just because a date
elapsed. Operators must securely remove eligible records after verifying both
conditions. This retention and the daily-backup/monthly-restore responsibility
come from [ADR 0003](https://github.com/SysKata-project/docs/blob/main/decisions/0003-data-lifecycle-and-operations.md).

## Backup and restore

The deployment operator owns daily backups of application **and Keycloak** data,
30-day expiry measured from original creation (including copies), and monthly
isolated restore tests. Protect dumps as sensitive learner data; never write them
to Git or upload raw dumps as CI artifacts. Keep the evidence database independently
recoverable; restoring an older application snapshot must not overwrite it.

Restore into isolated databases with API, identity and lab access unavailable:

1. Verify snapshot provenance/age and restore application and identity databases
   with matching PostgreSQL tools (`pg_dump -Fc`, `pg_restore --exit-on-error`).
2. Apply required migrations and validate checksums. Use the current independent
   deletion ledger; if it is missing or cannot be reconciled, keep services closed.
3. Reapply every ledger identity deletion to restored Keycloak, remove corresponding
   application records, and reconcile actual lab resources with the lab service.
   Already-completed jobs must also be reapplied when restoring an older snapshot;
   the normal pending-job worker alone is insufficient for restoration.
4. Verify deleted identities cannot log in or use old tokens, remaining learners'
   data/ownership constraints survive, and health/readiness pass before reopening.
5. Record only sanitized version/check results. Preserve original backup expiry;
   restoration or copying must not restart the 30-day clock.

`scripts/verify-restore.sh` exercises PostgreSQL dump/restore in the disposable test
container, preserves an active account, and removes an account deleted **after**
the snapshot using the independent ledger. It also restores the Keycloak database;
it does not claim to test a deployed multi-service recovery or lab cleanup.

## Verification

```sh
sh scripts/test-database.sh start
./mvnw -B -ntp clean verify
.venv-operations/bin/python -m unittest discover -s tests/operations -v
JAVA_HOME=/path/to/pinned/jdk .venv-operations/bin/python scripts/verify-keycloak.py
sh scripts/verify-restore.sh
python3 scripts/prove-quality-gates.py
sh scripts/scan-secrets.sh history
sh scripts/test-database.sh stop
```

Set `CONTAINER_ENGINE=docker` where appropriate. The fixture binds trust-authenticated
PostgreSQL to loopback port 55432 and contains synthetic data only; never use that
fixture for real learners. The real-identity smoke test uses loopback ports 58180
and 58080, generated private temporary credentials, the accepted Code/PKCE flow,
and a synthetic account. It stops its API/identity containers and removes its
network afterward. The local development Compose environment requires passwords
and is separate from this disposable fixture.

CI runs Java gates, operation tests, real Keycloak/packaged-API smoke and isolated restore; reports contain only
synthetic/sanitized results. Full production restore, scheduled backups, deployment
TLS, mobile callbacks and lab integration remain their linked implementation issues.

Local cookie testing follows the [localhost Secure-cookie exception](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie).
The Python smoke client implements that exception only for localhost. For browsers
that do not support it (including Safari), configure local HTTPS; do not disable
Secure cookies in a deployed realm.
