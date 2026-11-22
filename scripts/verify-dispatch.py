#!/usr/bin/env python3
"""Prove packaged dispatch restart against a disposable synthetic PostgreSQL fixture."""
import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
from uuid import uuid4
import psycopg


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--port', type=int, choices=(55432, 55435), default=55432)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    learner, session, instance, lab, job = (uuid4() for _ in range(5))
    credential = str(uuid4()) + str(uuid4())  # ephemeral loopback-only service credential
    state = {'requests': 0, 'record': None}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def reply(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if self.headers.get('Authorization') != 'Bearer ' + credential:
                self.reply(403, {})
                return
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path != '/internal/labs' or body['id'] != str(lab):
                self.reply(400, {})
                return
            state['requests'] += 1
            if state['record'] is None:
                state['record'] = {'id': str(lab), 'subject': str(learner), 'scenario': 'foundations',
                                   'generation': 1, 'revision': 1, 'state': 'ready',
                                   'expiresAt': (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
                                   'cleanupComplete': False}
                self.reply(503, {})  # Persisted remotely, acknowledgment lost.
            else:
                self.reply(200, state['record'])

        def do_GET(self):
            if self.headers.get('Authorization') != 'Bearer ' + credential or state['record'] is None:
                self.reply(503, {})
            else:
                self.reply(200, state['record'])

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    try:
        with psycopg.connect(host='127.0.0.1', port=args.port, user='syskata', dbname='syskata', autocommit=True) as db:
            try:
                with db.transaction():
                    db.execute("INSERT INTO learners(id,issuer,subject) VALUES (%s,'https://identity.test/realms/syskata',%s)", (learner, 'dispatch-proof-' + str(learner)))
                    db.execute("INSERT INTO enrollments(learner_id,journey_id) VALUES (%s,'fixture.dispatch')", (learner,))
                    version = db.execute('SELECT bundle_version FROM content_releases ORDER BY bundle_version LIMIT 1').fetchone()[0]
                    db.execute("INSERT INTO learning_sessions(id,learner_id,journey_id,bundle_version,policy_version,mode,status,selection_state) VALUES (%s,%s,'fixture.dispatch',%s,'1.0.0','lesson','active','{\"response\":{}}')", (session, learner, version))
                    db.execute("INSERT INTO exercise_instances(id,session_id,learner_id,exercise_id,exercise_revision,evaluator_id,evaluator_version,presentation) VALUES (%s,%s,%s,'fixture.dispatch',1,'openshift.foundations','1','{}')", (instance, session, learner))
                    db.execute("INSERT INTO lab_sessions(id,learner_id,session_id,instance_id,status,scenario) VALUES (%s,%s,%s,%s,'provisioning','foundations')", (lab, learner, session, instance))
                    payload = json.dumps({'id': str(lab), 'key': str(lab), 'subject': str(learner), 'scenario': 'foundations'})
                    db.execute("INSERT INTO outbox_jobs(id,learner_id,operation,deduplication_key,payload) VALUES (%s,%s,'provision',%s,%s::jsonb)", (job, learner, str(job), payload))
                env = {**os.environ, 'SYSKATA_DB_URL': f'jdbc:postgresql://127.0.0.1:{args.port}/syskata',
                       'SYSKATA_EVIDENCE_URL': f'jdbc:postgresql://127.0.0.1:{args.port}/syskata_evidence',
                       'SYSKATA_DB_USER': 'syskata', 'SYSKATA_EVIDENCE_USER': 'syskata',
                       'SYSKATA_DB_PASSWORD': 'synthetic-test-only', 'SYSKATA_EVIDENCE_PASSWORD': 'synthetic-test-only',
                       'SYSKATA_OIDC_ISSUER': 'https://identity.test/realms/syskata',
                       'SYSKATA_LABS_URL': f'http://127.0.0.1:{server.server_port}', 'SYSKATA_LABS_TOKEN': credential}
                java = str(Path(env['JAVA_HOME']) / 'bin/java')
                for run in range(2):
                    process = subprocess.run([java, '-Dquarkus.profile=dispatch', '-jar', str(args.jar.resolve()), '--once'],
                                             env=env, capture_output=True, timeout=45)
                    if process.returncode:
                        raise RuntimeError('Packaged dispatch process failed; no raw output published')
                    record = db.execute('SELECT attempts,completed_at FROM outbox_jobs WHERE id=%s', (job,)).fetchone()
                    if run == 0:
                        assert record[0] == 1 and record[1] is None
                        db.execute('UPDATE outbox_jobs SET available_at=now() WHERE id=%s', (job,))
                    else:
                        assert record[1] is not None
                assert state['requests'] == 2
                assert db.execute('SELECT status FROM lab_sessions WHERE id=%s', (lab,)).fetchone()[0] == 'ready'
                args.report.write_text(json.dumps({'packaged_process_restart': True, 'lost_acknowledgment_replayed': True,
                                                   'single_remote_allocation': True, 'outbox_completed': True}, indent=2) + '\n')
            finally:
                db.execute('DELETE FROM learners WHERE id=%s', (learner,))
    finally:
        server.shutdown()
        server.server_close()
    print('Packaged dispatch restart and lost-acknowledgment recovery passed')


if __name__ == '__main__':
    main()
