# Atomic attempt grading

Tracking: [api #4](https://github.com/SysKata-project/api/issues/4).
The [owner-approved interim boundary](development-decisions.md#grading-and-progress-ownership)
keeps review schedules, daily goals and streaks in api #6.

## Interface and transaction

OpenAPI contract 1.1.0 adds `PartialProgress` as an alternative in attempt results.
The five authenticated attempt operations implement start/resume, read, hint,
reveal and submission. Every response is `no-store`; mutations require a UUID
idempotency key. Ownership uses the authenticated learner and session, including
historical attempt reads. Other learners' resources return the same 404 as missing
resources. Disabled accounts cannot read results or replay requests.

Mutations lock the learner before the session. The attempt/result, per-skill
independent/assisted/mistake counters, session queue and revision, lesson completion
fact, first-completion reward, progress revision and exact replay response commit
in one PostgreSQL transaction. Exceptions roll everything back. Same-key/same-body
retries return the original response, including its timestamp and old progress
revision; same-key/different-body retries conflict. A new key cannot grade a
submitted attempt again. The attempt primary key, session lock, unique open-attempt
index and reward business uniqueness constraints enforce these boundaries.

Starting an equivalent open attempt is a no-op after revision validation. A first
hint/reveal advances the revision once; repeating it under a new key is a no-op
only at the current revision. Assistance is saved before text is returned and
survives interruption. A subsequent attempt starts without assistance. Concurrent
requests serialize; a client with an old revision must fetch session state before
constructing a new request. Stale errors include the current revision and
`refresh_state`; an instance mismatch uses `result_mismatch`.

## Evaluation and progression

Production text capabilities are exactly `choice.exact:1`, `order.exact:1` and
`command.literal:1`. Choice IDs must be issued options; ordering must be a complete
permutation of issued steps. An invalid answer shape/ID is a 400, not a mistake.
Commands are compared as data and never executed. `literal-command-v1` removes
only leading/trailing ASCII spaces, rejects controls/DEL and preserves internal
spacing, case, quotes, flags and operators. Unsupported evaluator versions fail
closed. The bundle version and exact question presentation come from the stored
session, even after catalog activation changes. Lab tasks cannot use text grading.

Feedback uses pinned authored criterion/misconception explanations and references.
Correct assisted submissions satisfy objectives but stay assisted evidence. Reveal
alone never completes an objective. Missing objectives remain pending after a
mistake. Remaining first-pass work runs in stable order, then remediation uses the
first missed objective and prefers an alternate exercise from the same lesson.
Already satisfied objectives are skipped. Completion requires all pending
objectives to be satisfied; no attempt/lives cap is introduced.

The first completion of a stable lesson awards 10 XP. A full lesson replay records
a new timestamped completion fact but awards no additional XP, including across
content versions. `lesson_completion_events` stores session, learner, lesson,
server timestamp, timezone and local date for future api #6 processing. Its primary
key deduplicates each session's completion and deletion cascades with the learner.
Attempt history retains the raw answer and pinned result in the database, never
in logs or public question payloads. No checkpoint award or trusted lab completion
is manufactured by this implementation.

## Partial progress and compatibility

Grading returns `progress.status: partial`, revision, confirmed timestamp, total
XP, completed lesson/checkpoint IDs and per-skill evidence counters. It omits
review due dates/intervals, daily goals, streaks and timezone-transition state.
Missing values mean unavailable, not zero, false or an empty schedule. The original
full `Progress` remains in the contract for api #6 and full-progress fixtures;
`GET /v1/me/progress` is not implemented here.

Explicit review-session creation returns `409 ineligible`; automatic selection
chooses eligible lesson work and does not select due review. Existing active
lesson sessions still resume before considering a requested mode. A stored review
session cannot be resumed for mutation while this boundary applies. Review
selection code remains tested for api #6, but no runtime setting enables review.
No grading operation updates `review_schedule` or grants review XP.

Consumers of contract 1.0.0 require an adapter update before using these grading
responses. In particular, the current Flutter `Progress` parser requires streak
and daily-goal fields: app #2 must handle the partial variant explicitly and must
not display invented daily values. Existing fixture-only app previews are not
changed or represented as integrated. API #6 must retain historical partial
responses for idempotent replay even after it begins producing full snapshots;
it must define how retained grading/completion facts initialize its schedules
and accounting without double credit.

The actual seven-lesson pilot artifacts remain drafts and cannot pass normal
release loading. Registering text capabilities does not publish or activate them.
Trusted checkpoint A before administration and checkpoint B before pilot
completion still require labs #3 / api #5 integration and compatible released
content. No live lab, mobile or deployed pilot validation is claimed here.

## Validation

Run the commands in [development guidance](development.md), including the complete
Java gates, contract fixtures and actual-response checks. The grading tests cover
assistance/replay, all pilot text exercise variants, invalid command/control input,
remediation, per-attempt evidence, concurrent same/different keys, transaction
rollback after grading, lost-response recovery, ownership, disabled accounts,
stale revisions/instances and content-version stability. Validation results and
remaining environmental limits are recorded in [validation evidence](validation.md).
