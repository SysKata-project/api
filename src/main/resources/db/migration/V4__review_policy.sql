ALTER TABLE review_schedule ADD COLUMN policy_version text NOT NULL DEFAULT '1.0.0';
CREATE TABLE timezone_changes (
 id uuid PRIMARY KEY,
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 old_timezone text NOT NULL,
 new_timezone text NOT NULL,
 outgoing_start timestamptz NOT NULL,
 effective_at timestamptz NOT NULL,
 full_day_start timestamptz NOT NULL,
 UNIQUE(learner_id,effective_at),
 CHECK(outgoing_start < effective_at AND effective_at <= full_day_start)
);
-- Before this migration review issuance was disabled. Reconstruct the first
-- success and subsequent adverse evidence; ordinary independent practice never
-- advances a scheduled interval. Stored response JSON and completion facts stay intact.
WITH evidence AS (
 SELECT a.learner_id, skill.value AS skill_id, a.submitted_at,
        a.result->>'outcome' = 'correct' AS correct,
        a.result->>'evidence' = 'assisted' AS assisted
 FROM attempts a JOIN exercise_instances i ON i.id=a.instance_id
 CROSS JOIN LATERAL jsonb_array_elements_text(i.presentation->'skill_ids') skill
 WHERE a.status='graded'
), first_success AS (
 SELECT learner_id,skill_id,min(submitted_at) AS first_at
 FROM evidence WHERE correct GROUP BY learner_id,skill_id
), baseline AS (
 SELECT f.learner_id,f.skill_id,
 greatest(f.first_at,max(e.submitted_at) FILTER (WHERE NOT e.correct OR e.assisted)) AS anchor
 FROM first_success f JOIN evidence e USING(learner_id,skill_id)
 GROUP BY f.learner_id,f.skill_id,f.first_at
)
INSERT INTO review_schedule(learner_id,skill_id,due_at,interval_days)
 SELECT learner_id,skill_id,anchor+interval '24 hours',1 FROM baseline
 ON CONFLICT DO NOTHING;

CREATE TABLE review_completion_events (
 learner_id uuid NOT NULL,
 skill_id text NOT NULL,
 due_at timestamptz NOT NULL,
 session_id uuid NOT NULL,
 PRIMARY KEY(learner_id,skill_id,due_at),
 FOREIGN KEY(learner_id,skill_id) REFERENCES skill_progress ON DELETE CASCADE,
 FOREIGN KEY(session_id,learner_id) REFERENCES learning_sessions(id,learner_id) ON DELETE CASCADE
);

-- Date labels can repeat across a timezone move. Deduplicate actual local-day
-- periods, while retaining the original credited date and zone for display.
ALTER TABLE reward_events ADD COLUMN practice_day_start timestamptz;
UPDATE reward_events SET practice_day_start=local_day::timestamp AT TIME ZONE timezone WHERE kind='review';
ALTER TABLE reward_events ADD CHECK ((kind='review') = (practice_day_start IS NOT NULL));
DROP INDEX one_daily_review_reward;
CREATE UNIQUE INDEX one_daily_review_reward ON reward_events(learner_id,activity_id,practice_day_start) WHERE kind='review';
