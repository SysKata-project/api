# Java development and quality gates

This repository currently contains only the scaffold needed to validate tooling
for [api #9](https://github.com/SysKata-project/api/issues/9). It implements no
HTTP endpoints, authentication, database persistence or learning features.
Owner-approved choices are in [development decisions](development-decisions.md).

## Reproducible local setup

Install Eclipse Temurin **25.0.5+8**, set `JAVA_HOME` to its JDK directory, and put
`$JAVA_HOME/bin` on `PATH`. The same version is pinned in CI and `.java-version`.
The build accepts Java 25 only; do not enable preview language features.
Import `pom.xml` into the IDE with Java language level 25 and the Maven Wrapper.
Delegate test/build execution to Maven when comparing results with CI.

Install the pinned Gitleaks version and run `sh scripts/install-hooks.sh` as
specified in [secret protection](../SECRET-PROTECTION.md). Keep both hooks enabled.

Run the complete local and CI verification:

```sh
./mvnw -B -ntp clean verify
```

This compiles Java, runs plain JUnit and in-process `@QuarkusTest` integration
tests, packages the minimal Quarkus application, and enforces coverage, PMD,
copy/paste detection, Checkstyle and formatting. It needs network access for the
initial dependency downloads; it requires no database, container engine or
credentials. The wrapper pins Maven **3.9.16**, wrapper **3.3.4**, and verifies
the Maven distribution SHA-256. No system Maven installation is required.

Focused commands (the complete verification remains required before review):

```sh
./mvnw test
./mvnw pmd:check pmd:cpd-check
./mvnw checkstyle:check
./mvnw spotless:check
./mvnw spotless:apply
python3 scripts/prove-quality-gates.py
sh scripts/scan-secrets.sh history
```

`spotless:apply` is the explicit formatting fix command; verification never
rewrites source. The gate proof script requires Python 3 and runs deliberate
failures in disposable copies. It checks failure diagnostics, restores corrected
sources, and verifies the complete build again. Logs and a machine-readable
summary go to `target/gate-evidence/`. An optional `GATE_MAVEN_REPO` environment
variable chooses an isolated dependency cache for the proof script.

## Tool ownership and pins

| Tool | Version | Responsibility |
| --- | --- | --- |
| Quarkus platform/plugin | 3.40.1.1 | Java 25 application and test lifecycle |
| JaCoCo | 0.8.16 | Combined JVM coverage and thresholds |
| Maven PMD plugin / PMD engines | 3.28.0 / 7.29.0 | Correctness, complexity and duplication |
| Maven Checkstyle plugin / Checkstyle | 3.6.0 / 14.4.0 | Naming, explicit imports and one top-level class |
| Spotless / Google Java Format | 3.11.0 / 1.30.0 | Canonical Java formatting |
| Surefire | 3.6.1 | Plain JUnit and Quarkus test execution |
| Compiler / Enforcer | 3.14.1 / 3.6.2 | Java compilation and build-tool constraints |

The remaining lifecycle plugins are explicitly pinned in `pom.xml`. Quarkus
manages JUnit and extension versions through its pinned BOM. All PMD language
engines and core are pinned together to avoid binary incompatibility. The
formatter pin is validated on Java 25; do not assume a newer formatter supports
the same javac internals.

Checkstyle intentionally has no indentation, whitespace or line-length checks.
PMD uses the explicit rules in `config/pmd.xml`: empty catches, resource leaks,
catching `Throwable`, unused locals, direct stack-trace printing, and cyclomatic
complexity. CPD rejects production-code duplicate blocks of **100 tokens or
more**; no file exclusions or ignore annotations are configured. This catches
substantial duplication without rejecting short conventional methods. Checkstyle
and formatting include test code; PMD/CPD initially cover production Java.

## Coverage scope and instrumentation

Coverage must reach **80% lines and 80% branches across the single production
module** (JaCoCo BUNDLE scope). All production classes in `target/classes` form
the denominator, including classes never loaded by tests. No application class,
package, DTO or scaffold exclusion is configured. Test sources are not production
coverage targets. Future generated-code exclusions require a documented review;
no manual exclusion is currently approved.

Plain JUnit uses the JaCoCo agent; the agent excludes `*QuarkusClassLoader` so
`quarkus-jacoco` instruments Quarkus-loaded classes exactly once. Both write the
same execution file with append/reuse enabled. Maven generates the final report
after test execution. Always use `clean verify` for acceptance to discard stale
coverage. The two scaffold tests intentionally cover different branches, and
the proof script verifies that neither test alone satisfies the branch gate.

The current integration scope is in-process Quarkus injection/runtime testing.
Packaged-process `@QuarkusIntegrationTest`, native-image and database tests are
not present and are not claimed as verified. Extend instrumentation and the gate
proofs when those scopes are introduced in implementation issues.

## Coding and component boundaries

Use `org.syskata.api` as the root package. Keep the current tooling fixture in
`scaffold`; remove or replace it with real behavioral tests when features arrive.
Future packages should group cohesive capabilities such as identity, catalog,
sessions, assessment, progress and labs. Within each capability keep transport
adapters separate from application operations and domain rules; persistence and
external clients belong at the adapter boundary. Favor constructor injection,
small cohesive classes, explicit types and immutable values. Use `UpperCamelCase`
for types, `lowerCamelCase` for methods/variables and `UPPER_SNAKE_CASE` for constants.

Keep a single modular application until a concrete issue requires a separate
artifact. Do not add cross-module database access or execute learner commands
on the API host. The API owns durable application state; lab execution belongs
to the labs component and authored curriculum to content. Do not introduce new
business policies through a tooling fixture.

Handle expected failures with explicit domain outcomes or narrowly typed
exceptions. Preserve causes when translating infrastructure errors; never swallow
exceptions or catch `Throwable`. Map errors to stable API envelopes at the
transport boundary once api #1 defines the contract. Retry only failures known
to be retryable and preserve idempotency; infrastructure failures must not become
learner mistakes.

Use parameterized JBoss Logging with bounded, allowlisted diagnostic fields.
Never log tokens, credentials, authorization headers, connection strings,
learner answers, identity payloads or raw request/response bodies. Use correlation
IDs rather than learner identifiers. Avoid `System.out` and `printStackTrace`;
log exceptions once at the responsible boundary and redact sensitive messages.
Reports uploaded by CI must contain synthetic test data only.

Tests should assert observable behavior and failure paths. Plain unit tests cover
pure rules; `@QuarkusTest` verifies container wiring and adapters. Add ownership,
replay/concurrency and transaction-failure tests with the corresponding features.
A high coverage percentage does not replace assertions or runtime evidence.

## Exceptions, updates and CI

Do not disable checks to merge a change. Propose any suppression in the PR with
the exact rule/location, false-positive evidence, narrowest scope, alternatives,
owner and removal/review condition. Obtain project-owner or authorized-reviewer
approval before adding it. Coverage exceptions are limited to documented generated
code; excluding ordinary production logic requires a new owner decision. Do not
add broad suppressions or change thresholds incidentally.

Review dependency and JDK security updates regularly and promptly after an
advisory. Update pins, wrapper checksum, IDE guidance and CI together in a focused
PR. Run `clean verify` and all gate proofs on the proposed toolchain; do not use
floating versions or silently change the Java major version. Cross-repository
runtime changes must remain consistent with the shared architecture decisions.

The `Java quality` workflow runs the **Java verification** job on pull requests,
main pushes and manual dispatch. `Secret scanning` supplies **gitleaks** separately.
Require both checks before merge. Required-check configuration and observed
validation results are recorded in [validation evidence](validation.md).

| Evidence | Local location |
| --- | --- |
| Test results | `target/surefire-reports/` |
| Coverage HTML/XML | `target/site/jacoco/` |
| Combined execution data | `target/jacoco.exec` |
| PMD / CPD XML | `target/pmd.xml`, `target/cpd.xml` |
| Checkstyle XML | `target/checkstyle-result.xml` |
| Failure/recovery evidence | `target/gate-evidence/summary.json` |

CI uploads these selected reports for seven days, including reports available
when verification fails. Raw gate logs stay local to avoid publishing arbitrary
process output; the summary is sufficient for the CI proof result.

## Primary references

- [Quarkus coverage and class-loader configuration](https://quarkus.io/guides/tests-with-coverage/)
- [Quarkus release support](https://quarkus.io/releases/)
- [PMD Java support](https://pmd.github.io/pmd/pmd_languages_java.html)
- [Checkstyle Java support](https://checkstyle.org/)
- [Maven Wrapper](https://maven.apache.org/wrapper/)
