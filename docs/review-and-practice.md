# Review, daily goals and streaks

Tracking: [api #6](https://github.com/SysKata-project/api/issues/6).
Policy `1.0.0` implements the [accepted learning policy](https://github.com/SysKata-project/docs/blob/main/decisions/0002-learning-policy.md).
XP is motivational feedback; independent and assisted evidence remain separate.
Learning, remediation and review have no lives limit, Redis or ML dependency.

## Review and rewards

After initial correct skill evidence, review is due after 24 elapsed hours.
Independent completion of the issued due review advances to 7, then 15 days;
subsequent independent reviews retain 15 days. Mistakes and assisted successes
reset an existing schedule to 1 day. A skill with only mistakes remains
unscheduled until its first correct completion. UTC due instants do not move
with daylight saving or timezone changes. Ordinary independent lesson practice
cannot advance a scheduled interval.

Resume active work first. Otherwise `auto` selects the earliest eligible due
skill, then the next eligible unfinished lesson. Explicit `review` returns
`409 ineligible` when no eligible skill is due. Due instant, skill ID and exercise
ID break selection ties deterministically. Each review session targets one skill;
its issued due instant survives remediation. Remediation repeats the selected
exercise, preserves prior failed-review evidence, and cannot accelerate the
interval. Assistance is still per attempt. A stale due event changed by intervening
evidence cannot advance an interval as a fresh review.

The learner lock serializes text grading, trusted lab grading, timezone changes
and progress reads. Schedule, evidence, rewards, completion facts and replayable
response commit together. `review_completion_events` claims each learner/skill/
issued-due-instant once, including across journeys. A correct due review awards
5 XP once per skill/local day; assistance is permitted. Review completion never
creates a lesson-completion fact or lesson XP. Existing 10 XP first-lesson and
25 XP first-checkpoint awards are unchanged. Infrastructure failures leave review
schedules and practice credit untouched.

## Calendar accounting

`GET /v1/me/progress` returns full confirmed progress: evidence, UTC review dates,
intervals, XP, completed lessons/checkpoints, timezone, pending change, local date,
daily goal, current/longest streak and transition-day marker. Snapshot revisions
increase under the learner lock. `next_action: session_complete`, new reward
events and confirmed completed IDs provide milestone feedback without suggesting
that XP proves mastery.

One fully completed lesson qualifies each local day; a full replay qualifies
without awarding more lesson XP. Review or lab completion alone does not qualify.
A checkpoint that also satisfies every remaining lesson objective can complete
that lesson. Stored completion timestamps are authoritative, including historical
replays; reading or retrying completion never creates another event. Past missed
full days reset current streak to zero; an unfinished current day does not.
Longest streak remains available after a miss.

Practice periods are ordered by UTC boundaries and named IANA zones, rather than
calendar-label subtraction. This handles 23/25-hour DST days and skipped or repeated
date labels. Completed lesson facts retain their credited date and zone. The
summary derives streaks from retained completion instants and immutable timezone
history; it does not reinterpret history using the current zone.

## Timezone changes

`POST /v1/me/timezone` requires a UUID idempotency key and one `timezone` field.
The change takes effect at next midnight in the active zone. History records old
and new zones, the outgoing-day start, effective instant and first full new-zone
day. During a partial transition day, completion can satisfy that day's goal but
neither increases nor breaks the streak and never pre-credits the next full day.
The outgoing day's per-skill review award guard lasts until the partial day ends.
A change landing exactly at destination midnight starts a full day immediately.

Owner decision, 2026-11-22: keep a pending change and reject a different requested
destination with `409 ineligible` until its transition ends. Repeating the same
destination returns the existing change. The same idempotency key always returns
its original response; a different payload conflicts. Requesting the active zone
with no pending transition is a no-op. No credentials are involved.

## Upgrade and consumer compatibility

Migration V4 initializes schedules from retained graded attempts: first correct
skill result, or the latest subsequent mistake/assisted success, plus 24 hours.
Prior review issuance was disabled, so no historical review advancement or review
XP is invented. Existing schedules are preserved. Historical lesson completion
facts initialize goal/streak accounting directly; no extra completion or XP rows
are manufactured. All new history has learner deletion cascades.

Stored historical attempt and idempotency responses remain byte-equivalent JSON
values with `progress.status: partial`. New results and progress reads return full
`Progress`. Contract 1.2.0 continues to accept both; no schema change is required.
Consumers must explicitly recognize partial history and omit unavailable day/review
fields rather than inventing values. The Flutter scaffold currently accepts full
snapshots, but its historical partial-response adapter remains required in
[app #2](https://github.com/SysKata-project/app/issues/2); this API change does not
claim mobile integration or pilot deployment. The runtime response validator
covers both new progress endpoints alongside grading and trusted lab responses.

## Validation

Run `./mvnw -B -ntp clean verify`, then the documented contract validation commands.
Tests cover deterministic review progression/remediation, replay and daily reward
deduplication, local midnight, DST, missed days, transition guards, repeated/skipped
dates, overlapping timezone requests and historical partial-response replay.
See [validation evidence](validation.md) for observed results and limits.
