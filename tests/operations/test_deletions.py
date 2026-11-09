import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from uuid import uuid4
import psycopg

spec = importlib.util.spec_from_file_location('deletions', Path(__file__).resolve().parents[2] / 'scripts/process-deletions.py')
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class DeletionTests(unittest.TestCase):
    def setUp(self):
        self.app = psycopg.connect('host=localhost port=55432 user=syskata dbname=syskata', autocommit=True)
        self.ledger = psycopg.connect('host=localhost port=55432 user=syskata dbname=syskata_evidence', autocommit=True)
        self.app.execute('TRUNCATE learners CASCADE')
        self.ledger.execute('TRUNCATE deletion_requests')
        self.issuer = 'http://localhost:58180/realms/syskata'
        self.subject = str(uuid4())
        self.learner = uuid4()
        self.app.execute('INSERT INTO learners(id,issuer,subject) VALUES (%s,%s,%s)', (self.learner,self.issuer,self.subject))
        self.ledger.execute('INSERT INTO deletion_requests(id,issuer,subject) VALUES (%s,%s,%s)', (uuid4(),self.issuer,self.subject))

    def tearDown(self):
        self.app.close()
        self.ledger.close()

    def test_retry_preserves_deadline_and_completes_once(self):
        deadline = self.ledger.execute('SELECT delete_by FROM deletion_requests').fetchone()
        with patch.object(worker, 'delete_identity', side_effect=OSError('synthetic unavailable')):
            with self.assertRaises(OSError): worker.process(self.app,self.ledger,self.issuer)
        self.assertEqual(deadline,self.ledger.execute('SELECT delete_by FROM deletion_requests').fetchone())
        self.assertIsNone(self.ledger.execute('SELECT completed_at FROM deletion_requests').fetchone()[0])
        with patch.object(worker,'delete_identity') as identity:
            self.assertEqual(1,worker.process(self.app,self.ledger,self.issuer))
            self.assertEqual(0,worker.process(self.app,self.ledger,self.issuer))
            identity.assert_called_once_with(self.issuer,self.subject)
        self.assertEqual(0,self.app.execute('SELECT count(*) FROM learners').fetchone()[0])
        self.assertIsNotNone(self.ledger.execute('SELECT completed_at FROM deletion_requests').fetchone()[0])

    def test_lab_records_block_unverified_cleanup(self):
        bundle=str(uuid4()); session=uuid4(); instance=uuid4()
        self.app.execute("INSERT INTO content_releases(bundle_version,schema_version,source_commit,learner_sha256,assessment_sha256) VALUES (%s,'1.0.0',repeat('a',40),repeat('b',64),repeat('c',64))",(bundle,))
        self.app.execute("INSERT INTO enrollments(learner_id,journey_id) VALUES (%s,'test')",(self.learner,))
        self.app.execute("INSERT INTO learning_sessions(id,learner_id,journey_id,bundle_version,policy_version,mode,status) VALUES (%s,%s,'test',%s,'1.0.0','lesson','active')",(session,self.learner,bundle))
        self.app.execute("INSERT INTO exercise_instances(id,session_id,learner_id,exercise_id,exercise_revision,evaluator_id,evaluator_version,presentation) VALUES (%s,%s,%s,'test',1,'test','1.0.0','{}')",(instance,session,self.learner))
        self.app.execute("INSERT INTO lab_sessions(id,learner_id,session_id,instance_id,status) VALUES (%s,%s,%s,%s,'provisioning')",(uuid4(),self.learner,session,instance))
        with patch.object(worker,'delete_identity') as identity:
            with self.assertRaises(ValueError): worker.process(self.app,self.ledger,self.issuer)
            identity.assert_not_called()
        self.assertIsNone(self.ledger.execute('SELECT completed_at FROM deletion_requests').fetchone()[0])
        self.assertEqual(1,self.app.execute('SELECT count(*) FROM lab_sessions WHERE learner_id=%s',(self.learner,)).fetchone()[0])

    def test_never_calls_identity_at_untrusted_issuer(self):
        with patch.dict(os.environ, {'SYSKATA_OIDC_ISSUER':self.issuer}), patch.object(worker,'urlopen') as http:
            with self.assertRaises(ValueError): worker.delete_identity('https://attacker.invalid/realms/syskata','subject')
            http.assert_not_called()

    def test_database_transaction_rolls_back(self):
        with self.assertRaises(RuntimeError):
            with self.app.transaction():
                self.app.execute('DELETE FROM learners')
                raise RuntimeError('synthetic failure')
        self.assertEqual(1,self.app.execute('SELECT count(*) FROM learners').fetchone()[0])


if __name__ == '__main__': unittest.main()
