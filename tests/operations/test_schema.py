import unittest
from uuid import uuid4
import psycopg


class SchemaTests(unittest.TestCase):
    def test_rewards_versions_and_relationship_constraints(self):
        with psycopg.connect('host=localhost port=55432 dbname=syskata user=syskata',autocommit=True) as db:
            learner=uuid4()
            db.execute('INSERT INTO learners(id,issuer,subject) VALUES (%s,%s,%s)',(learner,'schema-test',str(learner)))
            bundle=str(uuid4())
            db.execute("INSERT INTO content_releases(bundle_version,schema_version,source_commit,learner_sha256,assessment_sha256) VALUES (%s,'1.0.0',repeat('a',40),repeat('b',64),repeat('c',64))",(bundle,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                db.execute("UPDATE content_releases SET schema_version='2.0.0' WHERE bundle_version=%s",(bundle,))
            reward="INSERT INTO reward_events(id,learner_id,kind,activity_id,local_day,timezone,xp) VALUES (%s,%s,%s,'skill.test',%s,'UTC',%s)"
            db.execute(reward,(uuid4(),learner,'review','2026-11-09',5))
            with self.assertRaises(psycopg.errors.UniqueViolation):
                db.execute(reward,(uuid4(),learner,'review','2026-11-09',5))
            db.execute(reward,(uuid4(),learner,'review','2026-11-10',5))
            db.execute(reward,(uuid4(),learner,'lesson',None,10))
            with self.assertRaises(psycopg.errors.UniqueViolation):
                db.execute(reward,(uuid4(),learner,'lesson',None,10))
            with self.assertRaises(psycopg.errors.CheckViolation):
                db.execute(reward,(uuid4(),learner,'checkpoint',None,1000))
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                db.execute("INSERT INTO review_schedule VALUES (%s,'missing-skill',now(),1)",(learner,))
            db.execute("INSERT INTO skill_progress(learner_id,skill_id) VALUES (%s,'skill.test')",(learner,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                db.execute("INSERT INTO review_schedule VALUES (%s,'skill.test',now(),2)",(learner,))
            db.execute('DELETE FROM learners WHERE id=%s',(learner,))
            self.assertEqual(0,db.execute('SELECT count(*) FROM reward_events WHERE learner_id=%s',(learner,)).fetchone()[0])
