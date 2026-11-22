# Durable lab orchestration

Tracking: [api #5](https://github.com/SysKata-project/api/issues/5).
The owner selected a separate dispatch process sharing the API database on
2026-11-22. The labs service and evaluator still use their own database and
cannot modify learner progress.

## Public interface and persistence

Contract 1.2.0 implements lab creation, status, reset, close, terminal tickets,
assessment submission/polling and authenticated result delivery. It adds
`POST/GET /v1/lab-sessions/{id}/fault-stage` for the foundations exercise's
learner-triggered fault. Stage responses contain only the lab ID, generation and
bounded state; they do not expose evaluator observations or execute submitted
commands. Mobile integration remains app #4.

The API authorizes creation against the stored current exercise instance and
open attempt, using the session's immutable bundle. Mutations lock learner then
session and commit the intent, replay response and outbox together. Assessment
submission freezes assistance. A replay returns the original response; it does
not authorize reuse of an expired ticket or generation. Reads are owner-scoped.
The labs service receives the API learner UUID as its opaque subject, keeping
issuer-qualified identities distinct without trusting caller-supplied identity.

The dispatcher claims a database advisory lock across replicas and processes the
oldest unfinished job per learner. External calls happen outside learner
transactions. A lost response reuses the same persisted operation/assessment ID.
Retries use exponential delays capped at 60 seconds. After eight unsuccessful
provisioning dispatches, the API exposes an infrastructure error while retaining
the durable intent for reconciliation. A close may be queued before allocation
finishes; it runs after the provisioning intent resolves, without losing cleanup
tracking. Polling reconciles lifecycle
state and cleanup evidence; older in-flight responses cannot undo reset/close
intent. A queued reset that loses its eligibility to expiry is retired without blocking
a subsequent close. The dispatcher refreshes control revisions only when the
remote generation/state proves the operation has not already taken effect; lost
acknowledgments retain the original replay body. The original remote lease is
preserved across reset. Terminal registration
is a bounded synchronous call so the ticket is registered before its plaintext
is returned; the gateway stores only its hash. Plaintext replay responses stay
in the API database and must be protected like other credential-bearing data.

Reset and close invalidate pending assessments and cancel attempts; a new attempt
starts without inherited assistance. Infrastructure outcomes and expiry cancel
work without adding mistakes or rewards. Late old-generation results affect only
their original assessment, including when a newer assessment is already pending.
A completed result remains readable after a later reset or close. A failed
assessment can be retried with a fresh attempt on the same ready lab. An incorrect
grade advances to remediation with a new instance; close the old instance's lab
and create a lab for the newly issued instance.

## Result boundary and progress

The dedicated OIDC tenant validates issuer, signature, expiry, Bearer token type
and audience `syskata-assessment-results`. The route additionally requires
`assessment-result-writer`; its subject must equal the persisted worker assignment.
Configure that subject explicitly; it is not the worker client ID. Learner tokens
cannot use this route. Restrict internal ingress independently of these checks.
See the [Quarkus tenant configuration](https://quarkus.io/version/3.40/guides/security-openid-connect-multitenancy/).

Callbacks must match assessment, lab, session, instance, attempt, generation and
schema/bundle/policy/exercise/evaluator versions. Observation time must be after
submission and before the lease deadline, and cannot be in the future. Each
criterion must appear exactly once with a boolean outcome. Feedback comes from
pinned content, not worker text. Only fixed infrastructure error codes are accepted.

Application of a current result atomically saves attempt feedback, skill evidence,
session advancement, checkpoint/lesson/review rewards, full progress and the durable
callback receipt. An identical callback returns `duplicate`, including with a new
idempotency key; conflicting evidence cannot replace the committed result. The
existing unique reward constraints prevent repeat checkpoint XP across resets or
content versions. A checkpoint grants 25 XP under its stable exercise ID; completing
the enclosing lesson separately follows the existing 10 XP policy. Review/day/streak
calculations follow [review and practice](review-and-practice.md). Historical
partial results remain exact snapshots.

## Build and configuration

Use the pinned Java/Maven toolchain and normal `clean verify` command. Build the
public API normally. Build a separate dispatch distribution from the same source:

```sh
./mvnw -B -ntp package -Dquarkus.package.main-class=dispatch -Dquarkus.profile=dispatch
java -Dquarkus.profile=dispatch -jar target/quarkus-app/quarkus-run.jar
```

Keep each build's full `quarkus-app` directory as a separate artifact. The dispatch
profile binds HTTP to loopback port 8091 and denies HTTP routes. Use `--once` to execute a single dispatch/reconciliation pass. It runs no timer
inside the public API. Deploy the two processes with the same API database and
deletion-ledger configuration. The API needs the lab service credential for
terminal registration; the dispatcher needs it for lifecycle/assessment intents.
Neither process receives OpenShift controller or evaluator credentials.

Set the credential-free values in `.env.example`: `SYSKATA_LABS_URL`,
`SYSKATA_LABS_TOKEN`, `SYSKATA_TERMINAL_URL` and
`SYSKATA_ASSESSMENT_WORKER_SUBJECT`. Require HTTPS/WSS; HTTP is allowed only for
loopback service tests. HTTP redirects are not followed, responses are bounded
to 64 KiB, connect timeout is three seconds and request timeout five seconds.
Configure the evaluator's existing OAuth client and result URL to point at this
API. Provision its exact audience and realm role in Keycloak; never put its client
secret in the mobile app or terminal gateway.

## Content and deployment boundaries

Only `openshift.foundations:1` and `openshift.administration:1` are admitted as lab
profiles. Stable exercise IDs are `checkpoint.oc-foundations` and
`checkpoint.oc-administration`; revision is 1. Check and feedback IDs must match
the worker's exact version-one criterion sets. Checks use the same evaluator and
version with empty parameters: the worker owns its immutable profile and accepts
no content-supplied commands or override parameters. Required checkpoint objectives must be distinct from text-only objectives so
text answers cannot satisfy the checkpoint requirement. Put each checkpoint in
its enclosing lesson and use that lesson as the prerequisite for the next slice.
Draft bundles still fail
production loading. Integration fixtures are explicitly synthetic; this change
does not release or activate the seven-lesson curriculum.

Account deny-ledger checks block API access and callback application. Dispatch
propagates denial to the labs service, which revokes terminal access and queues
cleanup. The existing deletion processor still refuses to erase accounts with
lab records: a lab-side purge/cleanup receipt API is not present in the current
labs contract. Keep this fail-closed restriction until that integration is
implemented; do not claim active-data deletion or deploy with learner data on the
basis of these tests. No live OpenShift, mobile, Helm or pilot release validation
is claimed by the API integration suite.

## Validation

`LabTest` exercises PostgreSQL transactions and authenticated HTTP routes with a
stateful lab-service double: restart recovery, lost dispatch acknowledgments,
replay, concurrent callbacks, binding failures, reset/late-result races, expiry,
infrastructure outcomes, assistance, terminal tickets, fault stage and rollback.
`LabClientTest` exercises actual loopback HTTP, redirects, size limits and invalid
configuration. `scripts/verify-dispatch.py` additionally launches the packaged dispatch process
twice against a loopback service that persists a provisioning request before
losing its acknowledgment. It checks durable retry and a single remote allocation.
It accepts only the synthetic fixture ports and removes its own database rows.
Actual response artifacts are validated against OpenAPI alongside
the existing learning responses. See [validation evidence](validation.md) for
measured checks and environmental limits.
