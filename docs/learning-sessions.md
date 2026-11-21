# Versioned journey and session engine

Tracking: [api #3](https://github.com/SysKata-project/api/issues/3).
The [accepted boundary](development-decisions.md#session-engine-acceptance-boundary)
is an engine tested with the authored pilot fixtures. Production activation remains
blocked on api #4 grading and labs #3 trusted checkpoint integration. No draft
curriculum is enabled in the application. Without compatible released content,
catalog/new-enrollment/new-session requests return `503 unsupported_content`.

## Implemented interface

The authenticated endpoints follow the existing [OpenAPI contract](../contracts/openapi.yaml):

- `GET /v1/journeys` lists the selected compatible catalog and references.
- `POST /v1/me/enrollments` creates or returns enrollment. The first enrollment
  chooses an IANA timezone; subsequent enrollments must match it. Timezone
  changes remain api #6. Enrollment IDs are stable opaque UUIDs.
- `GET /v1/me/journeys/{journey}` returns lessons with available/locked/completed
  states and unmet journey, unit, lesson and skill prerequisites.
- `POST /v1/learning-sessions` resumes the active session for the requested
  journey before considering a new mode/lesson. Under the approved api #4 interim boundary, `auto` chooses the first eligible
  unfinished lesson and explicit review returns `409 ineligible` until api #6.
  Explicit lesson mode allows an unlocked completed lesson replay.
- `GET /v1/learning-sessions/{session}` reads an owner-scoped persisted snapshot,
  including the open/awaiting-assessment attempt and its recorded assistance.
  Missing and other learners' sessions both return 404.

Responses are `no-store`. Mutations require UUID idempotency keys; their scope is
learner plus HTTP method and concrete path. Parsed object key ordering is ignored;
array ordering is retained. Duplicate JSON keys, trailing JSON values, unknown
request fields, invalid modes, missing IDs and non-IANA zones are rejected.

## Selection and durable state

Journey/unit/lesson presentation order controls next-lesson selection. Exercises
are queued in ascending stable ID order; authored option/step ordering is stored
with the issued instance. There is no randomization in policy `1.0.0`. All lesson
objective IDs remain pending initially. Bundle validation rejects missing coverage,
foreign lesson objectives, broken references, duplicate entity/objective IDs and
prerequisite cycles. Completing a prerequisite uses durable completion reward
records; independent and assisted skill successes both satisfy skill prerequisites.
XP does not measure mastery.

The retained review-selection engine (not enabled at the runtime boundary until
api #6) selects the earliest eligible due skill by UTC due instant, then skill ID,
and the first eligible exercise by stable ID. One due skill is selected per review
session. Unavailable skills are skipped without rewriting their schedule. Its
original due instant and skill ID are persisted for subsequent remediation/reward
handling; api #6 still owns updating review schedules and daily accounting.

`learning_sessions.selection_state` stores the lesson/mode, exercise queue,
pending objectives, remediation queue, original review eligibility and the public
response snapshot. `exercise_instances.presentation` stores the exact learner
question; the instance separately pins exercise revision and evaluator/version.
Schema/bundle/policy versions are pinned in the session response and database.
An issued instance initially has no attempt. Attempt creation, grading, objective
completion/remediation transitions and reward writes are implemented by
[api #4](attempt-grading.md). This engine
never awards XP or claims that simply viewing a question completes an objective.
The grading transaction must update the queue, pending objectives, issued instance,
snapshot and session revision together. Its remediation ordering is already
specified by the [contract](api-contract.md#catalog-sessions-and-attempt-lifecycle).

Mutations lock the learner row, then operate on the session. Creation and the
successful replay response commit together; failures roll back both. The database
also enforces one active session per learner/journey. Future grading/lab writers
must retain a consistent learner-before-session lock order when taking both locks.
Reads hold a session share lock while reading the current attempt, preventing a
mixed session/attempt snapshot when writers follow the session-lock contract.
New create keys do not replace active versions or presentation; exact-key replays
return the original response, even if a later read shows a newer revision.

## Packaging, activation and rollback

Use the content repository's schema/assessment/reference validation and immutable
publication workflow before approving any manifest hash. The runtime accepts
hash-pinned compiler artifacts, verifies artifact byte counts, SHA-256 hashes,
manifest/schema versions, exact evaluator requirements, learner/trusted projection
agreement and graph/coverage invariants. The approved hash is a trust anchor for
the compiler's full schema and source validation, not a replacement for that
release review. Never derive it from an unreviewed download during activation.

A future release deployment can set `SYSKATA_CONTENT_INDEX` to an operator-owned
local JSON index alongside directories named by bundle version. No learner can
supply paths, hashes or versions. The index shape is:

```json
{
  "active": "1.0.0",
  "releases": [
    {"version": "1.0.0", "manifest_sha256": "APPROVED_64_HEX_DIGEST"}
  ]
}
```

The placeholder intentionally fails verification. Each version directory must
contain `manifest.json`, `learner.json` and private `assessment.json`. Publish all
files before atomically replacing the index. No GitHub/network call occurs in a
learning request. The cache uses approved manifest hashes; cached verified bytes
remain immutable until restart. A restart revalidates files. A changed index is
validated before switching the in-process active pointer. A rejected index blocks
new catalog selection but does not prevent reading/resuming stored sessions.

All versions referenced by any session, including completed sessions, must remain
in the index. Reusing a version with different source/artifact hashes is rejected
against immutable database release metadata. Rollback selects an older verified
version for new sessions and path views; it never rewrites active sessions,
exercise presentation, attempts, completion rewards or skill evidence. Completed
sessions remain readable with no current question/attempt or pending objectives.

The initial api #3 production evaluator capability set was empty.
[API #4](attempt-grading.md) now implements and registers the three text evaluators;
the interim boundary disables due-review issuance until api #6.
Neither fixture evaluators nor the draft pilot can pass normal loading. Tests use
a package-scoped fixture reader; there is no HTTP endpoint or application setting
to enable it. Implemented grading and trusted lab adapters must explicitly extend
the production registry with tests in their owning issues. Trusted lab integration must
also enforce checkpoint A before administration and checkpoint B before pilot
completion, which the draft text-only catalogs do not encode. This change does
not authorize making those drafts release-eligible.

## Verification

Run `sh scripts/test-database.sh start`, then the pinned JDK's
`./mvnw -B -ntp clean verify`. Integration tests exercise the seven-lesson pilot,
ownership, concurrent same/different keys, failed-transaction rollback, enrollment
timezones, locked/available/completed paths, due-review selection, interruption,
assistance recovery, activation/rollback, cache reload, version retention and
immutable metadata rejection. Bundle tests cover compatibility, hash tampering,
projection leakage and malformed graphs. Fixtures and provenance live in
[`src/test/resources/content`](../src/test/resources/content/README.md).

After Java tests, run `.venv-contracts/bin/python scripts/validate-learning-responses.py`
with `requirements-contracts.txt` installed. This checks actual HTTP responses for
all five operations against the unchanged OpenAPI schema and fails if evidence is
missing. CI runs it after Java verification. Run the existing contract, operation,
identity/restore, quality-gate proof and secret-scan commands from
[development guidance](development.md) as well.

The api #3 checks alone do not claim production curriculum activation, grading, mobile
integration, a running lab gateway, or trusted checkpoint progression.
