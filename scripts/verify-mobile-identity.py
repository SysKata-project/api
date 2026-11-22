#!/usr/bin/env python3
"""Disposable HTTPS/PKCE smoke test; synthetic credentials never leave temp storage."""
import base64
import hashlib
import html
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import tempfile
import time
from http.cookiejar import CookieJar
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPCookieProcessor, HTTPSHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]
ENGINE = os.environ.get('CONTAINER_ENGINE', 'docker')
BASE = 'https://localhost:58443'
ISSUER = BASE + '/realms/syskata'
CALLBACK = 'org.syskata.app:/oauth2redirect'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def command(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True)


def main():
    name = 'syskata-mobile-' + secrets.token_hex(5)
    database, identity = name + '-db', name + '-id'
    password = secrets.token_urlsafe(32)
    learner_password = secrets.token_urlsafe(32)
    with tempfile.TemporaryDirectory(prefix='syskata-mobile-') as temporary:
        directory = Path(temporary)
        tls = directory / 'tls'
        tls.mkdir(mode=0o755)
        command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                '-keyout', str(tls / 'tls.key'), '-out', str(tls / 'tls.crt'),
                '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost')
        (tls / 'tls.key').chmod(0o444)
        (tls / 'tls.crt').chmod(0o444)
        realm = json.loads((ROOT / 'dev/syskata-realm.json').read_text())
        realm['users'] = [{'username': 'mobile-smoke', 'enabled': True,
                           'firstName': 'Synthetic', 'lastName': 'Learner',
                           'email': 'mobile-smoke@example.invalid', 'emailVerified': True,
                           'credentials': [{'type': 'password', 'value': learner_password, 'temporary': False}]}]
        realm_path = directory / 'realm.json'
        realm_path.write_text(json.dumps(realm))
        realm_path.chmod(0o644)
        client = build_opener(NoRedirect(), HTTPCookieProcessor(CookieJar()),
                              HTTPSHandler(context=ssl.create_default_context(cafile=str(tls / 'tls.crt'))))

        def call(url, data=None, token=None):
            headers = {}
            if data is not None:
                headers['Content-Type'] = 'application/x-www-form-urlencoded'
                data = urlencode(data).encode()
            if token:
                headers['Authorization'] = 'Bearer ' + token
            try:
                response = client.open(Request(url, data=data, headers=headers), timeout=10)
            except HTTPError as error:
                response = error
            with response:
                return response.status, response.headers, response.read().decode()

        try:
            command(ENGINE, 'network', 'create', name)
            command(ENGINE, 'run', '-d', '--name', database, '--network', name,
                    '--network-alias', 'database', '-e', 'POSTGRES_DB=keycloak',
                    '-e', 'POSTGRES_USER=keycloak', '-e', 'POSTGRES_PASSWORD=' + password,
                    'docker.io/library/postgres:17.11')
            for _ in range(60):
                if command(ENGINE, 'exec', database, 'pg_isready', '-U', 'keycloak', check=False).returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Disposable identity database did not become ready')
            command(ENGINE, 'run', '-d', '--name', identity, '--network', name,
                    '-p', '127.0.0.1:58443:8443',
                    '-v', str(tls) + ':/opt/keycloak/conf/mobile-tls:ro',
                    '-v', str(realm_path) + ':/opt/keycloak/data/import/realm.json:ro',
                    '-e', 'KC_DB=postgres', '-e', 'KC_DB_URL=jdbc:postgresql://database:5432/keycloak',
                    '-e', 'KC_DB_USERNAME=keycloak', '-e', 'KC_DB_PASSWORD=' + password,
                    '-e', 'KC_HOSTNAME=' + BASE, '-e', 'KC_HTTP_ENABLED=false', '-e', 'KC_LOG_LEVEL=ERROR',
                    '-e', 'KC_HTTPS_CERTIFICATE_FILE=/opt/keycloak/conf/mobile-tls/tls.crt',
                    '-e', 'KC_HTTPS_CERTIFICATE_KEY_FILE=/opt/keycloak/conf/mobile-tls/tls.key',
                    'quay.io/keycloak/keycloak:26.8.2', 'start', '--import-realm')
            discovery_url = ISSUER + '/.well-known/openid-configuration'
            for _ in range(120):
                try:
                    status, _, body = call(discovery_url)
                    if status == 200:
                        break
                except (URLError, TimeoutError):
                    pass
                time.sleep(1)
            else:
                raise RuntimeError('Disposable HTTPS Keycloak did not become ready')
            discovery = json.loads(body)
            assert discovery['issuer'] == ISSUER
            for field in ['authorization_endpoint', 'token_endpoint', 'userinfo_endpoint', 'revocation_endpoint']:
                assert discovery[field].startswith(ISSUER + '/'), field
            try:
                build_opener().open(discovery_url, timeout=5)
            except URLError:
                pass
            else:
                raise AssertionError('Untrusted test certificate was unexpectedly accepted')
            verifier = secrets.token_urlsafe(48)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
            state, nonce = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
            parameters = {'client_id': 'syskata-mobile', 'redirect_uri': CALLBACK, 'response_type': 'code',
                          'scope': 'openid profile', 'code_challenge': challenge, 'code_challenge_method': 'S256',
                          'state': state, 'nonce': nonce}
            status, _, body = call(discovery['authorization_endpoint'] + '?' + urlencode(parameters))
            assert status == 200, 'Authorization did not present the login page'
            action = html.unescape(re.search(r'<form[^>]+action="([^"]+)"', body).group(1))
            assert action.startswith(ISSUER + '/'), 'Unexpected login action'
            status, headers, _ = call(action, {'username': 'mobile-smoke', 'password': learner_password})
            assert status == 302, 'Synthetic learner login failed'
            location = headers['Location']
            assert location.startswith(CALLBACK + '?'), 'Incorrect native callback'
            values = parse_qs(urlsplit(location).query)
            assert values['state'] == [state]
            status, _, body = call(discovery['token_endpoint'], {
                'grant_type': 'authorization_code', 'client_id': 'syskata-mobile',
                'redirect_uri': CALLBACK, 'code': values['code'][0], 'code_verifier': verifier})
            assert status == 200, 'PKCE token exchange failed'
            tokens = json.loads(body)
            claims = json.loads(base64.urlsafe_b64decode(tokens['id_token'].split('.')[1] + '==='))
            assert claims['iss'] == ISSUER and claims['nonce'] == nonce
            status, _, body = call(discovery['userinfo_endpoint'], token=tokens['access_token'])
            assert status == 200 and json.loads(body)['sub'] == claims['sub']
            status, _, body = call(discovery['token_endpoint'], {
                'grant_type': 'refresh_token', 'client_id': 'syskata-mobile', 'refresh_token': tokens['refresh_token']})
            assert status == 200, 'Refresh failed'
            refreshed = json.loads(body)
            status, _, _ = call(discovery['revocation_endpoint'], {
                'client_id': 'syskata-mobile', 'token': refreshed['refresh_token'], 'token_type_hint': 'refresh_token'})
            assert status == 200, 'Revocation failed'
            status, _, body = call(discovery['token_endpoint'], {
                'grant_type': 'refresh_token', 'client_id': 'syskata-mobile', 'refresh_token': refreshed['refresh_token']})
            assert status == 400 and json.loads(body)['error'] == 'invalid_grant'
            parameters['redirect_uri'] = 'org.syskata.app:/unregistered'
            status, _, _ = call(discovery['authorization_endpoint'] + '?' + urlencode(parameters))
            assert status == 400, 'Unregistered native callback was accepted'
            print('HTTPS discovery, exact callback, Code/PKCE, nonce, userinfo, refresh and revocation passed')
        finally:
            command(ENGINE, 'rm', '-f', identity, database, check=False)
            command(ENGINE, 'network', 'rm', name, check=False)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print subprocess arguments, URLs, bodies or generated credentials.
        print('Mobile identity smoke failed (' + type(error).__name__ + '); inspect the local test environment.')
        raise SystemExit(1) from None
