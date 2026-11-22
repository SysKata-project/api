-- Immutable completion facts include replays without granting additional XP.
-- api #6 consumes these confirmed timestamps; no daily/streak calculation occurs here.
CREATE TABLE lesson_completion_events (
 session_id uuid PRIMARY KEY,
 learner_id uuid NOT NULL,
 lesson_id text NOT NULL,
 confirmed_at timestamptz NOT NULL,
 timezone text NOT NULL,
 local_day date NOT NULL,
 FOREIGN KEY (session_id,learner_id) REFERENCES learning_sessions(id,learner_id) ON DELETE CASCADE
);
