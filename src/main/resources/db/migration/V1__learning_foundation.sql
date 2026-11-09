CREATE TABLE learners (
 id uuid PRIMARY KEY,
 issuer text NOT NULL CHECK (length(issuer) BETWEEN 1 AND 2048),
 subject text NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
 disabled_at timestamptz,
 timezone text NOT NULL DEFAULT 'UTC',
 progress_revision bigint NOT NULL DEFAULT 0 CHECK (progress_revision BETWEEN 0 AND 9007199254740991),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (issuer, subject)
);
CREATE TABLE content_releases (
 bundle_version text PRIMARY KEY,
 schema_version text NOT NULL,
 source_commit text NOT NULL CHECK (source_commit ~ '^[0-9a-f]{40}$'),
 learner_sha256 text NOT NULL CHECK (learner_sha256 ~ '^[0-9a-f]{64}$'),
 assessment_sha256 text NOT NULL CHECK (assessment_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE enrollments (
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 journey_id text NOT NULL,
 enrolled_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (learner_id, journey_id)
);
CREATE TABLE learning_sessions (
 id uuid PRIMARY KEY,
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 journey_id text NOT NULL,
 bundle_version text NOT NULL REFERENCES content_releases,
 policy_version text NOT NULL,
 mode text NOT NULL CHECK (mode IN ('lesson','review')),
 status text NOT NULL CHECK (status IN ('active','completed')),
 revision bigint NOT NULL DEFAULT 0 CHECK (revision BETWEEN 0 AND 9007199254740991),
 selection_state jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(selection_state) = 'object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (id, learner_id),
 FOREIGN KEY (learner_id, journey_id) REFERENCES enrollments ON DELETE CASCADE
);
CREATE UNIQUE INDEX one_active_session ON learning_sessions (learner_id, journey_id) WHERE status = 'active';
CREATE TABLE exercise_instances (
 id uuid PRIMARY KEY,
 session_id uuid NOT NULL,
 learner_id uuid NOT NULL,
 exercise_id text NOT NULL,
 exercise_revision integer NOT NULL CHECK (exercise_revision > 0),
 evaluator_id text NOT NULL,
 evaluator_version text NOT NULL,
 presentation jsonb NOT NULL CHECK (jsonb_typeof(presentation) = 'object'),
 issued_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (id, session_id, learner_id),
 FOREIGN KEY (session_id, learner_id) REFERENCES learning_sessions(id, learner_id) ON DELETE CASCADE
);
CREATE TABLE attempts (
 id uuid PRIMARY KEY,
 instance_id uuid NOT NULL,
 session_id uuid NOT NULL,
 learner_id uuid NOT NULL,
 status text NOT NULL CHECK (status IN ('open','awaiting_assessment','graded','cancelled')),
 assisted boolean NOT NULL DEFAULT false,
 hints jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(hints) = 'array'),
 revealed boolean NOT NULL DEFAULT false,
 answer jsonb,
 result jsonb,
 started_at timestamptz NOT NULL DEFAULT now(),
 submitted_at timestamptz,
 CHECK ((status = 'graded') = (result IS NOT NULL)),
 UNIQUE (id, instance_id, session_id, learner_id),
 FOREIGN KEY (instance_id, session_id, learner_id) REFERENCES exercise_instances(id, session_id, learner_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX one_open_attempt ON attempts(session_id) WHERE status IN ('open','awaiting_assessment');
CREATE TABLE skill_progress (
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 skill_id text NOT NULL,
 independent_successes integer NOT NULL DEFAULT 0 CHECK (independent_successes >= 0),
 assisted_successes integer NOT NULL DEFAULT 0 CHECK (assisted_successes >= 0),
 mistakes integer NOT NULL DEFAULT 0 CHECK (mistakes >= 0),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (learner_id, skill_id)
);
CREATE TABLE review_schedule (
 learner_id uuid NOT NULL,
 skill_id text NOT NULL,
 due_at timestamptz NOT NULL,
 interval_days integer NOT NULL CHECK (interval_days IN (1,7,15)),
 PRIMARY KEY (learner_id, skill_id),
 FOREIGN KEY (learner_id, skill_id) REFERENCES skill_progress ON DELETE CASCADE
);
CREATE TABLE reward_events (
 id uuid PRIMARY KEY,
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 kind text NOT NULL CHECK (kind IN ('lesson','review','checkpoint')),
 activity_id text NOT NULL,
 local_day date,
 timezone text NOT NULL,
 xp integer NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK ((kind = 'review') = (local_day IS NOT NULL)),
 CHECK ((kind = 'lesson' AND xp = 10) OR (kind = 'review' AND xp = 5) OR (kind = 'checkpoint' AND xp = 25))
);
CREATE UNIQUE INDEX one_completion_reward ON reward_events(learner_id,kind,activity_id) WHERE kind <> 'review';
CREATE UNIQUE INDEX one_daily_review_reward ON reward_events(learner_id,activity_id,local_day) WHERE kind = 'review';
CREATE TABLE lab_sessions (
 id uuid PRIMARY KEY,
 learner_id uuid NOT NULL,
 session_id uuid NOT NULL,
 instance_id uuid NOT NULL,
 generation bigint NOT NULL DEFAULT 1 CHECK (generation BETWEEN 1 AND 9007199254740991),
 status text NOT NULL CHECK (status IN ('provisioning','ready','resetting','closing','closed','expired','infrastructure_error')),
 expires_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK (status <> 'ready' OR expires_at IS NOT NULL),
 UNIQUE (id, instance_id, session_id, learner_id),
 FOREIGN KEY (instance_id, session_id, learner_id) REFERENCES exercise_instances(id, session_id, learner_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX one_live_lab ON lab_sessions(instance_id) WHERE status IN ('provisioning','ready','resetting','closing');
CREATE TABLE assessments (
 id uuid PRIMARY KEY,
 lab_id uuid NOT NULL,
 instance_id uuid NOT NULL,
 session_id uuid NOT NULL,
 learner_id uuid NOT NULL,
 attempt_id uuid NOT NULL,
 generation bigint NOT NULL CHECK (generation > 0),
 assigned_worker text NOT NULL,
 status text NOT NULL CHECK (status IN ('pending','completed','infrastructure_error','expired','superseded')),
 result jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (lab_id,instance_id,session_id,learner_id) REFERENCES lab_sessions(id,instance_id,session_id,learner_id) ON DELETE CASCADE,
 FOREIGN KEY (attempt_id,instance_id,session_id,learner_id) REFERENCES attempts(id,instance_id,session_id,learner_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX one_pending_assessment ON assessments(lab_id,generation,attempt_id) WHERE status = 'pending';
CREATE TABLE outbox_jobs (
 id uuid PRIMARY KEY,
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 operation text NOT NULL,
 deduplication_key text NOT NULL UNIQUE,
 payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
 available_at timestamptz NOT NULL DEFAULT now(),
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 completed_at timestamptz
);
CREATE TABLE idempotency_records (
 learner_id uuid NOT NULL REFERENCES learners ON DELETE CASCADE,
 operation text NOT NULL,
 idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
 request_sha256 text NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
 response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (learner_id,operation,idempotency_key)
);

-- A release identifies immutable bytes. Activate/select releases in a separate
-- application operation, never by rewriting an existing metadata row.
CREATE FUNCTION protect_content_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Content release metadata is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER immutable_content_release BEFORE UPDATE OR DELETE ON content_releases
 FOR EACH ROW EXECUTE FUNCTION protect_content_release();
