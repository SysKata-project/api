# Quality-gate validation evidence

Issue: https://github.com/SysKata-project/api/issues/9
Validation date: 2026-11-06.

## Local baseline

- macOS ARM64; Eclipse Temurin 25.0.5+8; Maven Wrapper 3.3.4 / Maven 3.9.16.
- `./mvnw -B -ntp clean verify`: passing compilation, two tests, Quarkus
  packaging, JaCoCo, PMD, CPD, Checkstyle and Spotless.
- Coverage: 4/4 lines and 2/2 branches (100% each) across the single production
  fixture class. This tiny scaffold demonstrates instrumentation, not application
  test completeness.
- The PMD engines are aligned at 7.29.0; mixing the newer Java engine with the
  plugin's older core failed at runtime and was corrected before validation.
- Google Java Format 1.30.0 works with the selected Java 25 toolchain. Version
  1.37.0 failed through javac reflection and 1.28.0 was rejected by Spotless's
  Java-version guard; neither remains in the build.
- `sh scripts/scan-secrets.sh history`: passed with no findings before publication.

## Deliberate failure and recovery proofs

Run `python3 scripts/prove-quality-gates.py` after normal verification. It creates
only disposable copies of build inputs; it never mutates application sources in
the working checkout. The default build contains no deliberate failures.

The script requires the expected diagnostic as well as a nonzero exit code, so
an unrelated dependency or compiler failure does not count as proof of a gate.
Its final clean build proves corrected examples pass again.

| Probe | Required result |
| --- | --- |
| Undefined symbol | Compiler failure naming the symbol |
| Incorrect expected result | Test assertion failure |
| Plain unit test alone | Branch coverage 50%, rejected |
| Quarkus test alone | Branch coverage 50%, rejected |
| Additional untested class | Line coverage rejected; unexecuted classes count |
| Unused local variable | PMD `UnusedLocalVariable` failure |
| Two substantial duplicate blocks | CPD duplication failure |
| Uppercase method name | Checkstyle `MethodName` failure |
| Malformed whitespace | Spotless formatting failure |
| Explicit formatter fix | Original canonical source restored |
| Corrected source | Complete clean verification passes |

Execution results are recorded in `target/gate-evidence/summary.json` and in the
CI report artifact. The proof script's result is itself required by the Java
verification job.

## Repository enforcement

GitHub's `main` branch protection was configured and read back on the validation
date. It requires **Java verification** and **gitleaks**, requires an up-to-date
branch, and enforces the checks for administrators. No previous branch protection
or ruleset existed. This does not replace the working agreement's separate
review approval and merge authorization requirements.

## Scope limits

No HTTP business feature, identity provider, PostgreSQL runtime, packaged-process
integration test, native image, deployment or OpenShift behavior is validated by
this tooling issue. Those remain in their implementation issues. Final PR CI
results and review status are visible on the linked PR; local success alone is
not approval or merge.

## Service foundation evidence — 2026-11-09

For [api #2](https://github.com/SysKata-project/api/issues/2), on the same pinned
Java/Maven toolchain, macOS ARM64 and Podman:

- Clean verification passed: 8 Java tests, packaging, PMD/CPD, Checkstyle and
  Spotless. Combined production coverage is 130/137 lines (94.89%) and 52/57
  branches (91.23%); the 80%/80% thresholds and absence of exclusions are unchanged.
- Five Python/PostgreSQL operation tests passed for rollback, deletion retries,
  deadline preservation, issuer confinement, lab-cleanup refusal, immutable release
  metadata, reward uniqueness, foreign keys and deletion cascades.
- Ten contract tests passed; OpenAPI 3.1.1 and all 59 fixtures validated.
- Real Keycloak 26.8.2 Code/PKCE S256 login passed against the packaged API.
  Access tokens worked; ID tokens were rejected. Accepted deletion immediately
  denied the still-valid token; the worker removed the synthetic Keycloak user
  and application data. Sanitized results: `target/identity-smoke.json`.
- PostgreSQL 17.11 migrations applied on a fresh disposable fixture and repeated
  startup validated migration history. Application and Keycloak dumps restored
  into isolated databases. Reconciliation removed a synthetic account deleted
  after the snapshot while preserving an active account; current evidence was
  not restored backward. Results: `target/restore-evidence/summary.json`.
- Source links, whitespace and secret scans are checked before publication.

The initial real-token test found the Keycloak client needed its `basic` scope
for the `sub` claim. The final realm includes it, and missing-subject tokens are
explicitly rejected. OIDC warning logging was disabled after observing that
library diagnostics can echo malformed token text. No real credentials or learner
data were used. The smoke client handles localhost Secure cookies explicitly;
this is not a deployed HTTP policy.

These checks do not validate native images, mobile callbacks, deployed backups,
full multi-service recovery, or future learning/lab endpoints. The pending lab
cleanup adapter deliberately prevents deletion jobs with recorded labs from being
marked complete. See the [service foundation](service-foundation.md) for limits
and the linked implementation issues.

## Journey/session engine — api #3

Validated locally on 2026-11-19 with the pinned JDK, Quarkus/Maven tools and
PostgreSQL 17.11 disposable fixture:

- `clean verify`: 21 passing Java tests, including seven database/HTTP session
  tests and six bundle/strict-JSON tests. Coverage: 717/734 lines (97.68%) and
  239/270 branches (88.52%); both 80% thresholds remain unchanged.
- PMD, CPD, Checkstyle and Spotless passed without suppressions or exclusions.
- Actual catalog, enrollment, journey, session-create and session-resume HTTP
  responses passed the existing OpenAPI schemas. The 10 contract tests and all
  59 existing positive/negative fixtures passed.
- Five existing operation/database tests passed, including rollback and deletion.
- All 12 deliberate-failure/recovery gate cases passed in disposable copies,
  including both isolated coverage instrumentation paths and the corrected build.
- GitHub `main` requires `Java verification` and `gitleaks`; the contract workflow
  additionally validates the existing API fixtures.
- Real Keycloak 26.8.2 Code/PKCE smoke passed, including access/ID-token
  distinction, immediate account denial and identity deletion. Isolated application
  and Keycloak database restore plus deletion reconciliation passed.

The owner-approved boundary uses draft pilot fixtures only. Production capability
registration remains empty; draft activation, actual grading, trusted checkpoint
progression and mobile integration are not claimed. See
[session implementation and activation boundaries](learning-sessions.md).

## API #4 atomic grading — 2026-11-21

Owner-approved scope and deferred calculations are recorded in
[development decisions](development-decisions.md#grading-and-progress-ownership)
and [attempt grading](attempt-grading.md). No draft curriculum is activated.

Local final-source validation on the pinned JDK/Maven toolchain:

- `./mvnw -B -ntp clean verify`: 31 tests passed, including real HTTP/PostgreSQL
  attempt transactions and simultaneous submissions/hint races. PMD, CPD,
  Checkstyle, Spotless, packaging and unchanged coverage gates passed.
- JaCoCo: 1,112/1,133 lines (98.15%) and 399/453 branches (88.08%).
- All ten actual learning/attempt endpoint response files pass OpenAPI validation.
  Ten contract tests and 62 positive/negative fixtures pass, including rejection
  of partial progress with invented daily fields or a missing partial marker.
- Five database/deletion-worker regression tests and all 12 quality-gate
  failure/recovery proofs passed. The proofs ran against the same production
  source before the final two additional regression tests were added.
- Local documentation links and whitespace checks passed. Full-history secret
  scanning passed before publication; staged/pre-push scans remain enabled.

The standard local database initialization first failed with PostgreSQL
`No space left on device`. A task-owned PostgreSQL 17.11 container with a 512 MiB
in-memory data mount allowed the database/HTTP checks to run without deleting
unrelated data. Podman still failed container-state operations with
`unable to open database file: no such file or directory`. Consequently the local
Keycloak/packaged-process smoke and restore scripts could not execute their first
container command. Those checks are **not locally verified** for this change;
PR CI runs both against a fresh Linux fixture before review readiness.

The app's current full-progress parser requires adaptation in app #2 before
runtime integration. Review schedule writes, review rewards, daily goals, streaks
and timezone changes remain api #6. Trusted checkpoint progression and awards,
pilot activation, live lab integration and mobile validation are not claimed by
this text-grading deliverable.

## API #5 lab orchestration — 2026-11-22

- Full Java `clean verify`: **47 tests passed**, zero failures/errors/skips.
  JaCoCo: **97.40% lines (1836/1885)** and **80.60% branches (677/840)**.
  PMD, CPD, Checkstyle and Spotless passed without exclusions or gate changes.
- OpenAPI 1.2.0: **23 operations, 64 positive/negative fixtures**; ten Python
  contract tests passed. Captured learning and lab HTTP responses validate against
  the contract, including pending/completed assessment and callback receipts.
- Packaged dispatch process launched twice against PostgreSQL and a loopback HTTP
  service. A persisted remote allocation with a lost acknowledgment survived
  process exit and completed on restart without allocating twice. The bounded
  report is generated at `target/dispatch-evidence/summary.json` and CI repeats it.
- Secret history scan and whitespace checks passed. Commit/push hooks remain active.
- Local JVM: pinned Temurin 25.0.5+8. The existing Podman VM's API/SSH connections
  did not respond, so local Java/dispatch tests used an isolated PostgreSQL **17.12**
  instance on loopback port 55435. Repository/CI PostgreSQL remains pinned to
  **17.11**. The existing container-based real-Keycloak, restore and gate-proof
  checks must run in CI; they were not represented as locally rerun.
- These tests prove API orchestration with synthetic content and controlled lab
  responses. They do not prove live OpenShift, mobile or deployed pilot behavior.
  Draft content remains inactive. The account-deletion purge/receipt limitation is
  explicitly recorded in [lab orchestration](lab-orchestration.md).
