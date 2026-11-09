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
