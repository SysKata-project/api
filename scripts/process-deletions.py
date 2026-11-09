#!/usr/bin/env python3
"""Run retryable deletion jobs. Never print identifiers, credentials or exceptions."""
import os
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, HTTPRedirectHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


urlopen = build_opener(NoRedirect).open
import psycopg


def delete_identity(issuer, subject):
    expected = os.environ['SYSKATA_OIDC_ISSUER'].rstrip('/')
    if issuer != expected or '/realms/' not in expected:
        raise ValueError('Issuer mismatch')
    base, realm = expected.rsplit('/realms/', 1)
    if not base.startswith('https://') and not base.startswith('http://localhost:'):
        raise ValueError('TLS required outside loopback development')
    url = base + '/admin/realms/' + quote(realm, safe='') + '/users/' + quote(subject, safe='')
    request = Request(url, method='DELETE', headers={
        'Authorization': 'Bearer ' + os.environ['SYSKATA_KEYCLOAK_ADMIN_TOKEN']})
    try:
        with urlopen(request, timeout=15) as response:
            if response.status != 204:
                raise ValueError('Unexpected identity result')
    except HTTPError as error:
        if error.code != 404:
            raise


def process(application, ledger, issuer):
    completed = 0
    # Hold row locks during bounded identity requests; another worker skips these jobs.
    with ledger.transaction():
        jobs = ledger.execute('SELECT id,issuer,subject FROM deletion_requests '
                              'WHERE completed_at IS NULL AND issuer=%s ORDER BY accepted_at '
                              'FOR UPDATE SKIP LOCKED LIMIT 20', (issuer,)).fetchall()
        for job_id, issuer, subject in jobs:
            with application.transaction():
                learner = application.execute('SELECT id FROM learners WHERE issuer=%s AND subject=%s '
                                              'FOR UPDATE', (issuer, subject)).fetchone()
                if learner:
                    application.execute('UPDATE learners SET disabled_at=COALESCE(disabled_at,now()) WHERE id=%s', learner)
                    # Until labs #2/#5 provides a teardown adapter, never claim cleanup
                    # or delete tracking rows for any provisioned lab, even a closed one.
                    lab = application.execute('SELECT 1 FROM lab_sessions WHERE learner_id=%s LIMIT 1', learner).fetchone()
                    if lab:
                        raise ValueError('Lab cleanup evidence required')
                delete_identity(issuer, subject)  # Keycloak deletion also removes sessions.
                if learner:
                    application.execute('DELETE FROM learners WHERE id=%s', learner)
            ledger.execute('UPDATE deletion_requests SET identity_deleted_at=now(), '
                           'labs_deleted_at=now(), completed_at=now() WHERE id=%s', (job_id,))
            completed += 1
    return completed


def main():
    try:
        with psycopg.connect(os.environ['SYSKATA_OPERATIONS_DB_DSN'], autocommit=True) as application, \
                psycopg.connect(os.environ['SYSKATA_OPERATIONS_EVIDENCE_DSN'], autocommit=True) as ledger:
            completed = process(application, ledger, os.environ['SYSKATA_OIDC_ISSUER'].rstrip('/'))
        print(f'Deletion pass completed: {completed} jobs')
    except Exception:
        # Driver and HTTP exceptions can contain URLs, identities and credentials.
        print('Deletion pass failed; access remains denied. Check service availability and cleanup prerequisites.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
