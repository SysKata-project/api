# Learning API contract 1.0.0

Status: proposed implementation contract for [api #1](https://github.com/SysKata-project/api/issues/1).
The OpenAPI document and fixtures are executable specifications, not running
endpoints. The accepted product decisions below remain authoritative. Review
of this PR approves the proposed protocol details; it does not verify a backend.

## Sources and compatibility

- [OpenAPI 3.1.1](../contracts/openapi.yaml) is the machine-readable HTTP contract.
- [Fixtures](../contracts/fixtures) cover every operation and positive/negative
  examples. [Validation](contract-validation.md) explains their limits.
- Accepted [learning policy](https://github.com/SysKata-project/docs/blob/44cfeb4922ea5c99f4f7741ce82ca6aeb15e5e63/decisions/0002-learning-policy.md),
  [identity baseline](https://github.com/SysKata-project/docs/blob/44cfeb4922ea5c99f4f7741ce82ca6aeb15e5e63/decisions/0001-backend-and-identity.md)
  and [data lifecycle](https://github.com/SysKata-project/docs/blob/44cfeb4922ea5c99f4f7741ce82ca6aeb15e5e63/decisions/0003-data-lifecycle-and-operations.md).
- [Content schema 1.0.0](https://github.com/SysKata-project/content/blob/18e80a23c86731862cd804e88988ff6cf7b076dc/docs/content-schema.md).
  The `Content_*` schemas reproduce its learner projection, with local references.
  Trusted assessment definitions are deliberately absent. The representative
  content uses fixture evaluators and is not released curriculum.

API `info.version` identifies this contract. `/v1` is the compatibility boundary;
breaking changes require a new major API path. With closed object schemas, new
fields are not automatically compatible: coordinate reader upgrades and publish
a revised contract before emitting them. Response enum additions also require
consumer compatibility review. Consumers must not silently interpret unknown
states as success.

Pin schema, immutable bundle and learning policy versions when creating a session.
Policy `1.0.0` means the accepted ADR 0002 rules, including its per-attempt
assistance amendment. Each issued instance also pins exercise revision and
evaluator/version; results repeat these identifiers for reproducibility.
Clients cannot select or override versions. Retain old readers/bundles for active
sessions. Unsupported versions prevent activation; return `unsupported_content`
without issuing an unusable question. Rollback affects selection for new sessions.

## Authentication and ownership

**Accepted owner decision, 2026-11-06:** Flutter signs in through the system
browser using Keycloak Authorization Code with PKCE S256. The mobile client is
public and contains no client secret. This decision was explicitly approved
while working api #1. Use state and OIDC nonce validation, exact registered
redirect URIs and platform secure storage. Do not use embedded password forms,
implicit flow or password grants. Refresh through Keycloak; on refresh failure,
return to sign-in. Clear account-scoped pending requests and caches on sign-out
or account switching. Tokens and terminal tickets never enter URLs or logs.

Proposed logical configuration names: realm `syskata`, mobile client
`syskata-mobile`, learner API audience `syskata-api`. Validate signature with
issuer-discovered keys, exact configured issuer, audience, expiry and not-before.
Keycloak ID tokens are not API access tokens. Identity is `(iss, sub)`, never
email or a submitted learner ID. Check disabled/deletion state on every API call,
including still-valid tokens and idempotency replays. Refresh or login cannot
resurrect a disabled account. Environment issuer URLs, platform callback URIs,
Keycloak version and token/session lifetimes are deployment inputs to api #2,
app #3 and docs #5, not hardcoded public endpoints in this specification.

Use a separate service-account principal and audience
`syskata-assessment-results`, with `assessment-result-writer` role, for the
internal result route. Learner tokens are rejected even if otherwise valid.
Match the worker principal to the persisted dispatch assignment. Worker secret
injection belongs to deployment configuration; never deliver it to a learner.
Use TLS for API and callback traffic. Restrict internal ingress independently of
token validation. All resource queries enforce ownership, including nested
session/attempt, lab and assessment bindings. Hide other learners' resources
behind the same 404 as nonexistent resources; IDs are not authorization.

## HTTP, retries and transaction ordering

JSON only; reject unknown fields, duplicate JSON object keys, malformed bodies,
unsupported media types and bodies over 64 KiB after decoding/decompression.
Validate string/array bounds in OpenAPI, IANA timezone membership and contextual
IDs. Responses include `Cache-Control: no-store`; error messages contain bounded
redacted operational descriptions, never answers, tokens or terminal transcripts.
The server creates the correlation ID in the stable error envelope. Examples
contain synthetic IDs and a deliberately unusable ticket.

Every mutation requires an `Idempotency-Key` UUID. Persist it on the device with
the exact logical payload before sending. Key scope is `(issuer, subject, HTTP
method, concrete resource path)`. Compare parsed JSON semantically: object key
order is insignificant; array order, strings and numbers retain their meaning.
Reject duplicate JSON keys before comparison. Keep the original successful
status and body atomically with the state change. Retain mutation replay records
for the life of the account, then delete them under the accepted deletion policy.
Internal result receipts remain for the assessment's retention lifetime.

Processing order:

1. Authenticate, enforce account status and authorize ownership/worker assignment.
2. Validate body and key; look up the scoped key. Same key/different payload is
   `409 idempotency_conflict`. An identical completed request returns its original
   status/body before revision, expiry or generation checks. This reports a past
   outcome, not permission to use an expired lab or terminal ticket.
3. Serialize concurrent requests for that key. An in-flight duplicate may wait or
   return `409 operation_pending` with `retryable: true` and
   `recovery: retry_same_request`. It must not execute twice.
4. For a new key, validate current revision, bindings, eligibility and lifetime;
   commit mutation, replay response and outbox work in one transaction. A lost
   response is recoverable with the same key. A failed transaction commits none.

Stale revision is `409 stale_revision` with the current session revision. Fetch
session state and reconcile; send a new key only for a new, intentional operation.
Transport failure/503 retries use the same key. Validation/stale errors before
commit are not stored as successful operations. HTTP 429/503 include Retry-After
seconds. Exponential backoff must respect that minimum. Do not blindly retry
nonretryable conflicts. Error recovery is a hint; it never overrides ownership.

All session-affecting mutations serialize on the learning session. Revision is
a monotonically increasing safe JSON integer. An actual mutation increments it
once; a replay or semantic no-op does not. Starting an attempt, first recording
assistance, submitting, lab create/reset/close, enqueueing assessment and applying
a current result affect the revision. Terminal ticket creation checks revision
but does not advance learning state. Provisioning status transitions alone do
not advance learning state. Worker results use trusted persisted bindings rather
than a client revision: lock and apply against the current generation/attempt.
A client sees the resulting revision on its next read. Global progress has its
own revision and confirmed timestamp; ignore older snapshots, including those
returned by replaying an earlier successful request.

Business uniqueness protects against new keys: enrollment per learner/journey,
one active session per learner/journey, one open attempt per session, one final
grade per attempt, one live lab per issued instance, one pending assessment per
lab generation/attempt and unique reward events. A second submission under a new
key for a finalized attempt returns `409 attempt_closed`, never another grade.
Return existing enrollment/session/open attempt/live lab for equivalent create
intent after revision validation, without mutating versions or evidence.

## Catalog, sessions and attempt lifecycle

Catalog lists compatible active journeys; the path response resolves unit,
lesson, reference and prerequisite information. The bounded MVP catalog is not
paginated; a future catalog exceeding the schema limits needs a versioned
pagination contract. Enrollment chooses the account IANA timezone on first use;
later enrollments must match the current timezone. Use the timezone operation
for changes. Prerequisites are checked by the API, not inferred from UI state.

Session creation resumes an active session for that journey before choosing new
work, even if requested mode differs. For `auto`, choose due review first,
otherwise the next unlocked lesson; `lesson` requires a lesson ID; `review`
selects due skills. If no requested work is eligible, return `409 ineligible`.
Completed lessons may be intentionally replayed. Completed sessions remain
readable; their current instance and attempt are null. Active sessions expose
one issued instance; a new instance has no attempt until `startAttempt`.

Proposed deterministic selection within the accepted policy: stable journey/unit/
lesson presentation order, then exercise ID order for objective coverage; due
skills sort by due instant then skill ID. Queue missed objectives at the end of
remaining first-pass work, in first-mistake order, deduplicated by objective ID.
If only remediation remains, issue it next. Prefer another eligible exercise for
that objective, then reuse the original if no alternative exists. Persist the
queue and any presentation ordering; reconnects do not rerandomize questions.
No numeric attempt cap or lives limit is introduced.

`startAttempt` creates the server attempt ID, or resumes the open attempt.
Hints/reveals bind to that ID before delivering text. Repeated use of the same
hint is a no-op after revision validation. Assistance survives app closure.
After a submitted attempt, a newly started attempt begins unassisted even if
prior attempts saw hints/reveals; prior history remains available. This is the
owner's explicit policy, not an assertion that prior exposure is forgotten.
An awaiting lab assessment freezes the attempt's assistance state: hints/reveals
return `409 attempt_closed` until it is resolved or cancelled.

Choice uses the issued option IDs; ordering requires each issued step exactly
once. An answer variant/ID set incompatible with the issued exercise is a 400,
not a learner mistake. Command input is never executed: apply content's
`literal-command-v1` rules (trim ASCII spaces at ends only; reject controls/DEL;
preserve internal spaces, case, quoting, flag order and operators). Syntactically
valid wrong answers return HTTP 200 with `outcome: incorrect` and criterion
feedback. Feedback reference IDs resolve in the response's documentation list.
The feedback contains authored explanations, not arbitrary worker-supplied text.

Atomically persist grade, assistance evidence, session advancement, skill/review
updates, reward events and replayable result. A correct assisted answer satisfies
objective completion; reveal alone does not. Complete only after every objective
has a qualifying correct result, including required real checkpoint evidence.
The returned next action is continue, remediate or session_complete; fetch the
session for the next issued question. The next action does not authorize a client
to choose its own next instance. Read attempt detail to recover a lost result.

## Progress and policy invariants

- First completion of a stable lesson grants 10 XP, guided or independent.
  Replays may qualify for daily goal/streak but grant no further lesson XP.
- Completing due review grants 5 XP per learner/skill/local day. Assistance is
  allowed; preserve due-review eligibility across same-session remediation.
- First trusted completion of a stable checkpoint grants 25 XP. Duplicate
  assessments, resets and new content versions do not grant another award.
- Review intervals are elapsed 24-hour days: 1, then 7, then 15; retain 15 after
  later independent success. Mistakes/assisted success reset to 1. Same-session
  remediation cannot accelerate review as if it were a later scheduled review.
- One completed lesson, including an eligible replay, satisfies the daily goal
  and streak. Review/checkpoint alone does not. An ended missed day resets the
  current streak; an unfinished current day does not. Preserve longest streak.
- Assign day/timezone using server-confirmed events and persist that attribution.
  Do not recalculate rewards or day credit on HTTP replay.

The progress object separates independent/assisted successes, mistakes, due
instants, XP, completed nodes and day/streak state. XP is not mastery. No client
score, balance, timestamp or claimed completion is accepted.

Timezone changes take effect at next midnight in the old timezone. A same-value
request is a no-op; a new request while a different change is pending returns
409 `ineligible` (read progress to recover pending state). Preserve historical
attribution, carry the outgoing day's per-skill reward guard through the first
partial day in the new timezone, and neither increase nor break streak during
that partial day. Normal streak counting resumes at the next new-zone midnight.
The contract returns the effective instant; validate IANA names server-side.
Scheduled review UTC instants do not move with timezone changes.

## Labs, terminal access and asynchronous assessment

Authorize a lab only for the current eligible lab exercise and its open attempt.
Persist provisioning and outbox work before 202. Poll the lab for readiness;
provisioning failure is an infrastructure state, not a wrong answer. Expiry is
server-owned runtime configuration; the client cannot extend it. A ready lab
requires a non-null expiry. `expires_at: null` means allocation has not yet
established a lease, not an unlimited lease. Check expiry at every operation and
worker-result application.

For terminal access, a separate POST issues a single-use ticket for at most
60 seconds and never beyond lab expiry. Response URLs contain no token or query
credentials. Connect over WSS and send the ticket as the first authentication
frame within 5 seconds; close unauthenticated connections. The gateway checks
subject, current lab/generation, account status, lease and ticket redemption
atomically before opening the terminal. Reconnect requires a new ticket/key;
replaying a consumed ticket response cannot create a second terminal. Ticket TTL
limits redemption, not lab duration. Reset, close, expiry and deletion revoke
active connections. Only learner credentials reach the terminal. The terminal
stream/frame protocol itself belongs to labs #2/app #4; these endpoints define
its authorization handshake, not a complete terminal transport implementation.

Lifecycle: provisioning → ready, or infrastructure_error; ready → resetting,
closing or expired; resetting → ready or infrastructure_error; closing → closed.
Expired/closed labs cannot be reset or assessed; create a new lab when eligible.
An infrastructure_error lab may be closed and recreated after cleanup. Return
409 for invalid transitions and 410 for an expired lease. Cleanup retries remain
server-side jobs; closing/expired is not evidence teardown already succeeded.

Reset checks revision and generation, increments generation exactly once,
invalidates outstanding assessments/tickets and cancels the current attempt.
Enqueue teardown/setup durably. The learner starts a new attempt after readiness;
old assistance remains in history. Closing likewise cancels any pending attempt
and invalidates results/access. Reset does not extend an already issued lease;
new allocation/lease requires closing then creating a new lab. A new assessment
requires ready state, the exact generation and an open attempt. It freezes that
attempt and returns 202 with Location, Retry-After and the new session revision.
The polling URL is authenticated and owner-scoped.

Polling returns HTTP 200 with a discriminated state: pending, completed (with the
normal attempt result), infrastructure_error, expired or superseded. Terminal
states do not change after publication; reset cannot erase a previously committed
completion. Infrastructure error has no grade/reward and does not reset review.
Cancel the frozen attempt on infrastructure failure or invalidation; the learner
may start a fresh attempt and assessment if the same lab remains ready, or close
and recreate a failed/expired lab. Reusing a terminal assessment request/key
returns the old assessment, not a new evaluation.

For internal delivery, validate path/body assessment ID equality, service-account
assignment, assessment/lab/session/instance/attempt/generation, pinned schema,
bundle/policy/exercise/evaluator versions and observation time within the lease.
The complete criterion ID set must match the pinned assessment checks exactly,
with no duplicates. Only boolean criterion outcomes or a bounded infrastructure
failure code are accepted; no learner IDs, XP, arbitrary evidence/commands,
feedback prose or trusted evaluator parameters are accepted in the callback.
The API maps criteria to feedback and derives correctness and progress.

Lock the assessment and session. Exact redelivery by assessment ID returns
`duplicate` even with a different idempotency key; no second mutation. A differing
second result returns 409 `result_conflict`. Mismatched bindings return 409
`result_mismatch`. Late results for pending work invalidated by reset/close return
`ignored_superseded`; expired work returns `ignored_expired`, even if observation
occurred earlier. Both acknowledge transport without learner penalties. Record
the ignored receipt so subsequent deliveries remain harmless. A valid current
result updates assessment, attempt, progress and replay receipt atomically.
Infrastructure results terminalize the job without creating an incorrect grade.
Authentication/authorization still precedes all replay/deduplication checks.

## Account deletion

The authenticated confirmation POST durably records a deletion job and original
accepted timestamp, disables account access and returns 202 with a deadline no
later than 30 days after acceptance. Immediate API denial must not rely on access
token expiry. Revoke identity sessions and terminal/lab access; retry cleanup and
Keycloak deletion independently without extending the original deadline.

After acceptance all learner endpoints, including deletion retries, return 403
`account_disabled`. This deliberately overrides normal replay availability:
there is no polling token or back door into a disabled account. A lost 202 is
reconciled as disabled account, not proof that permanent erasure is complete.
Operators verify completion through the deletion job; no learner-facing status
service is introduced. App sign-out clears local tokens/caches/pending work.

Active data is removed within 30 days; backup retention remains 30 days from
creation, including copies. Retain minimal deletion evidence until 30 days after
verified completion and longer only while restoration can reintroduce the
account. Before restored services reopen, reapply and verify subsequent deletions
using evidence independent of the restored snapshot. Do not log request bodies,
answers, authentication headers or terminal transcripts. Operational logs expire
within 30 days. These are implementation obligations, not tests passed here.

## Remaining implementation and review boundaries

Api #2 owns token configuration, persistence, deletion jobs and restore evidence;
api #3/#4 own selection/grading transactions; api #5 owns durable dispatch and
callback races; api #6 owns review/day transitions. Labs #2/#3 and app #3/#4 must
validate terminal interoperability and trusted worker authentication. Content #2
owns real bundles/capability manifests and release validation. Runtime quota,
lease/concurrency values come from the validated lab configuration; this contract
does not select new operating budgets or infrastructure.

No backend feature implementation or deployment occurs in this issue. API #9
remains the prerequisite for backend feature work. Required runtime tests include
concurrent keys, concurrent hints/submissions, reset/result and expiry/result
races, database rollback, lost responses, worker redelivery, cross-learner access,
content rollback, account deletion with valid tokens, and timezone/DST boundaries.

Protocol references: [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html),
[Keycloak OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers),
and [JSON Schema validation](https://python-jsonschema.readthedocs.io/en/stable/validate/).
