# API development tooling decisions

Tracking issue: https://github.com/SysKata-project/api/issues/9

This record distinguishes owner-approved choices from implementation details.
See [development guidance](development.md) and [validation evidence](validation.md)
for pinned versions, enforcement scope and verification.

## Accepted by the project owner on 2026-11-06

- Use Maven with a pinned Maven Wrapper for consistent local and CI builds.
- Enforce minimum 80% line and 80% branch coverage across production Java code,
  combining unit and Quarkus tests. Only documented generated-code exclusions
  are permitted; no exclusions are currently approved or configured.
- Enable PMD copy/paste detection as a failing build gate for production Java
  code. Configure and document the minimum duplicate-block size during tooling
  implementation.
- Use Checkstyle for Java naming and structural conventions, and Spotless with
  Google Java Format for automatic formatting. PMD owns code-quality and
  duplication rules; avoid overlapping formatting checks.

- Use Java 25 LTS with Quarkus 3.40 LTS; exact patch versions are pinned during
  implementation and must pass the full verification suite.

## Implementation details

- CPD rejects production duplicate blocks of 100 tokens or more.
- Coverage is enforced at the single production module (BUNDLE) scope.
- Exact versions and test scope are recorded in development guidance.

Backend business features remain outside this tooling issue.

## API foundation development identity defaults

Accepted by the project owner on 2026-11-09 while working api #2:

- Access tokens last 5 minutes; SSO sessions idle out after 30 minutes and have
  an 8-hour maximum lifetime. Offline tokens are disabled for the client.
- These are local development defaults, not a new deployed-session policy.

Implementation pins: PostgreSQL 17.11 and Keycloak 26.8.2. The imported realm
retains the accepted public-client Code/PKCE S256 flow. The exact loopback callback
is for local verification; mobile platform callbacks must be registered explicitly
in app #3/deployment configuration, without wildcard redirects.

## Session engine acceptance boundary

Accepted by the project owner on 2026-11-19 while working api #3: accept the
session engine validated with pilot fixtures. Production curriculum activation
remains gated on api #4 grading and labs #3 trusted checkpoint integration.
Draft bundles remain ineligible for production activation. This boundary does
not waive session persistence, prerequisite, compatibility or rollback tests.

## Grading and progress ownership

Accepted by the project owner on 2026-11-21 while working api #4: keep shared
review-schedule, daily-goal and streak calculations in api #6. Agree an interim
api #4 response contract rather than moving those calculations into grading.
The owner subsequently approved the interim contract: grading returns confirmed
XP, completed lesson/checkpoint IDs and skill evidence counters with explicit
`status: partial`. Review schedules, daily goals and streaks are omitted. Due-review
sessions remain unavailable until api #6. No placeholder values represent these
deferred calculations. Preserve timestamped grading/completion evidence for api #6;
this boundary defers api #4's review-change writes to api #6, without waiving
transactional attempts, skill evidence, lesson rewards or replay safety.

## Durable lab dispatch process

Accepted by the project owner on 2026-11-22 for api #5: run durable lab dispatch
in a separate Java/Quarkus process built from this repository, sharing the API
PostgreSQL database. The public API commits intents and replay responses before
external dispatch. Lab execution and trusted evaluation remain in the labs
repository and cannot write learning progress directly.
