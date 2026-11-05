# Learning API implementation design

Status: planned MVP baseline; endpoint names below are proposed contracts to formalize in OpenAPI.

## Runtime and storage

One modular API owns identity integration, catalog access, sessions, grading, progression, review and rewards. PostgreSQL is the durable store. Backend framework, identity provider and hosting require an early decision. Use an isolated worker boundary for asynchronous lab dispatch/results; no Redis requirement for MVP.

Initial tables: learners, enrollments, content_releases, learning_sessions, exercise_instances, attempts, skill_progress, review_schedule, reward_events, lab_sessions, assessments and outbox_jobs. Stable IDs, foreign keys, timestamps and uniqueness constraints are required. Answer/result JSONB supports exercise variants. Store bundle/exercise/evaluator/policy versions and skill evidence including hint/reveal usage. Enforce one completion reward per qualifying activity, not merely per HTTP request.

## Contract outline

| Endpoint | Behavior |
| --- | --- |
| GET /v1/journeys | Published compatible journey catalog |
| POST /v1/me/enrollments | Enroll in the OpenShift journey |
| GET /v1/me/journeys/{id} | Path with available, locked and completed nodes |
| POST /v1/learning-sessions | Resume or create a version-pinned session and issue its first exercise |
| GET /v1/learning-sessions/{id} | Confirmed position and current exercise |
| POST /v1/learning-sessions/{id}/attempts | Grade an issued instance, commit outcome, return feedback and next action |
| POST /v1/lab-sessions | Authorize/provision a lab for an eligible exercise instance |
| GET /v1/lab-sessions/{id} | Readiness, expiry and authorized connection information |
| POST /v1/lab-sessions/{id}/assessments | Persist assessment request; return 202 and status URL |
| GET /v1/assessments/{id} | Pending/result/infrastructure-error status |
| GET /v1/me/progress | Confirmed mastery evidence, review due, goals and streak |

Define reset, close, hint-use and account-deletion contracts with the same ownership/idempotency rules. Authentication subject determines user identity; no caller-supplied score or level is trusted. Validate session ownership and enforce request size limits. Return stable error codes, retryability, correlation ID and recovery guidance.

An attempt carries exercise_instance_id and an exercise-specific answer, plus an Idempotency-Key header. An instance records the prompt variant and option ordering so the answer cannot be reinterpreted later. A response contains attempt ID, outcome, criterion feedback, hints/documentation references, confirmed progress/rewards, next action and session revision.

## Transaction and failure rules

Atomically persist attempt, progression, review changes, reward events and replayable result. Scope idempotency by authenticated user and operation; same key/different payload returns conflict. Serialize session advancement or reject stale revisions. Unique reward constraints protect against a new key resubmitting an already completed instance. Check finite retry semantics per exercise.

Persist lab work with a transactional outbox. Authenticate incoming lab results, match assessment/session/instance and evaluator versions, and apply each result once. An expired or reset lab generation cannot complete a newer session. Timeouts and infrastructure faults do not become wrong answers. Retry dispatch safely, expose status, and bound retries with observable failure recovery.

## Selection, grading and policies

Resume first; otherwise choose due review or the next eligible lesson. Keep deterministic selection and versioned policy parameters for lesson completion, review intervals, daily goals and reward amounts. A success after hint/reveal has different skill evidence from an independent answer. Completion requires objective coverage and a defined retry/remediation policy. Specify timezone-change and qualifying-day rules before streak implementation.

Use option IDs for choice questions, explicit valid orderings for command assembly and constrained accepted forms for short commands. Never execute submitted commands in the API. Labs report observable system state; the API interprets it for learning progression. Feedback maps criterion/misconception codes to original content explanations and version-matched links.

## Content and operations

Load a validated immutable bundle packaged with the API. Serve only learner-facing projections. Fail activation if schema/evaluator capabilities are unsupported. Retain older bundles for active sessions; activation/rollback only changes selection for new sessions. Cache by hash in process; do not cache authoritative balances/progress initially.

Provide migrations, health checks, structured redacted logs, backup/restore and deletion/retention policies. Test concurrent/replayed submissions, ownership failures, restart recovery, content upgrades, lab redelivery and DB rollback. See [shared architecture](https://github.com/SysKata-project/docs/blob/main/architecture/learning-platform.md).

## Later lives and gifts

Keep out of MVP. If introduced, add transaction and gift tables with unique claim IDs, sender/recipient attribution, explicit cap/refill/expiry rules and atomic balance updates. Review remains unrestricted and infrastructure errors never consume lives.
