#!/usr/bin/env python3
"""Isolated Code/PKCE and packaged-API smoke test. Synthetic users only."""
import base64
import hashlib
import html
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import traceback
from http.cookiejar import CookieJar, DefaultCookiePolicy
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit, parse_qs
from urllib.request import Request, build_opener, HTTPRedirectHandler, HTTPCookieProcessor
import psycopg

ROOT = Path(__file__).resolve().parents[1]
ENGINE = os.environ.get('CONTAINER_ENGINE', 'podman')
BASE = 'http://localhost:58180'
ISSUER = BASE + '/realms/syskata'
API = 'http://localhost:58080'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class LoopbackCookies(DefaultCookiePolicy):
    # Browsers allow Secure cookies on localhost; urllib does not by default.
    # This exception is confined to the synthetic loopback test client.
    def return_ok_secure(self, cookie, request):
        if urlsplit(request.full_url).hostname == 'localhost':
            return True
        return super().return_ok_secure(cookie, request)


cookies = CookieJar(policy=LoopbackCookies())
client = build_opener(NoRedirect(), HTTPCookieProcessor(cookies))


def call(url, data=None, token=None, method=None, form=False):
    headers = {}
    if data is not None:
        headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        data = (urlencode(data) if form else json.dumps(data)).encode()
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if url.startswith(API):
        headers['Idempotency-Key'] = '33333333-3333-4333-8333-333333333333'
    try:
        response = client.open(Request(url, data=data, headers=headers, method=method), timeout=10)
    except HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read().decode()


def engine(*args):
    subprocess.run([ENGINE, *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def wait(url):
    for _ in range(120):
        try:
            if call(url)[0] == 200: return
        except (OSError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError('Service startup timeout')


def main():
    admin_password = secrets.token_urlsafe(32)
    password = secrets.token_urlsafe(32)
    network = 'syskata-foundation-smoke'
    name = 'syskata-foundation-test-keycloak'
    api = None
    (ROOT / 'target').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='syskata-identity-') as private:
        env_file = Path(private) / 'identity.env'
        env_file.write_text('KC_BOOTSTRAP_ADMIN_USERNAME=synthetic-admin\nKC_BOOTSTRAP_ADMIN_PASSWORD=' + admin_password + '\n')
        env_file.chmod(0o600)
        try:
            engine('exec','syskata-foundation-test-db','dropdb','-U','syskata','--force','--if-exists','keycloak')
            engine('exec','syskata-foundation-test-db','createdb','-U','syskata','keycloak')
            engine('network','create',network)
            engine('network','connect',network,'syskata-foundation-test-db')
            engine('run','-d','--name',name,'--network',network,'-p','127.0.0.1:58180:8080',
                   '--env-file',str(env_file),'-e','KC_DB=postgres','-e','KC_DB_URL=jdbc:postgresql://syskata-foundation-test-db:5432/keycloak',
                   '-e','KC_DB_USERNAME=syskata','-e','KC_HOSTNAME='+BASE,'-e','KC_LOG_LEVEL=ERROR',
                   '-v',str(ROOT/'dev/syskata-realm.json')+':/opt/keycloak/data/import/syskata-realm.json:ro',
                   'quay.io/keycloak/keycloak:26.8.2','start-dev','--import-realm')
            print('Checking identity startup', flush=True)
            wait(ISSUER+'/.well-known/openid-configuration')
            status,_,body=call(BASE+'/realms/master/protocol/openid-connect/token',
                              {'client_id':'admin-cli','grant_type':'password','username':'synthetic-admin','password':admin_password},form=True)
            assert status==200
            admin=json.loads(body)['access_token']
            username='synthetic-'+secrets.token_hex(8)
            print('Creating synthetic identity', flush=True)
            status,headers,_=call(BASE+'/admin/realms/syskata/users',
                                 {'username':username,'enabled':True,'emailVerified':True,'firstName':'Synthetic','lastName':'Test',
                                  'email':username+'@example.invalid','credentials':[{'type':'password','value':password,'temporary':False}]},admin)
            assert status==201
            subject=headers['Location'].rsplit('/',1)[1]
            verifier=secrets.token_urlsafe(48)
            challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
            state=secrets.token_urlsafe(24)
            status,_,body=call(ISSUER+'/protocol/openid-connect/auth?'+urlencode({
                'client_id':'syskata-mobile','redirect_uri':'http://localhost:8765/callback','response_type':'code',
                'scope':'openid','state':state,'nonce':'synthetic-nonce','code_challenge':challenge,'code_challenge_method':'S256'}))
            assert status==200
            action=html.unescape(re.search(r'<form[^>]*action="([^"]+)"',body).group(1))
            status,headers,login_body=call(action,{'username':username,'password':password},form=True)
            print('Login response status: ' + str(status), flush=True)
            assert status==302
            callback=parse_qs(urlsplit(headers['Location']).query)
            assert callback['state']==[state]
            status,_,body=call(ISSUER+'/protocol/openid-connect/token',{
                'client_id':'syskata-mobile','grant_type':'authorization_code','code':callback['code'][0],
                'redirect_uri':'http://localhost:8765/callback','code_verifier':verifier},form=True)
            assert status==200
            tokens=json.loads(body)
            assert 0 < tokens['expires_in'] <= 300
            env=os.environ.copy()
            env.update(SYSKATA_DB_URL='jdbc:postgresql://localhost:55432/syskata',SYSKATA_DB_USER='syskata',
                       SYSKATA_DB_PASSWORD='synthetic-only',SYSKATA_EVIDENCE_URL='jdbc:postgresql://localhost:55432/syskata_evidence',
                       SYSKATA_EVIDENCE_USER='syskata',SYSKATA_EVIDENCE_PASSWORD='synthetic-only',SYSKATA_OIDC_ISSUER=ISSUER)
            java=str(Path(os.environ['JAVA_HOME'])/'bin/java')
            with (ROOT/'target/identity-smoke-api.log').open('w') as log:
                api=subprocess.Popen([java,'-Dquarkus.http.host=127.0.0.1','-Dquarkus.http.port=58080','-jar','target/quarkus-app/quarkus-run.jar'],cwd=ROOT,env=env,stdout=log,stderr=log)
                wait(API+'/q/health/ready')
                endpoint=API+'/v1/me/account-deletion'
                assert call(endpoint,{'confirmation':'invalid'},tokens['id_token'])[0]==401
                status,_,diagnostic=call(endpoint,{'confirmation':'invalid'},tokens['access_token'])
                print('Access-token API status: '+str(status),flush=True)
                assert status==400
                assert call(endpoint,{'confirmation':'delete-account'},tokens['access_token'])[0]==202
                assert call(endpoint,{'confirmation':'delete-account'},tokens['access_token'])[0]==403
                spec=importlib.util.spec_from_file_location('worker',ROOT/'scripts/process-deletions.py')
                worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)
                os.environ['SYSKATA_OIDC_ISSUER']=ISSUER
                os.environ['SYSKATA_KEYCLOAK_ADMIN_TOKEN']=admin
                # Process only this real identity test job; synthetic unit-test jobs
                # remain in their test ledger and must not be sent to another issuer.
                with psycopg.connect('host=localhost port=55432 user=syskata dbname=syskata_evidence',autocommit=True) as ledger:
                    with psycopg.connect('host=localhost port=55432 user=syskata dbname=syskata',autocommit=True) as application:
                        assert worker.process(application,ledger,ISSUER)==1
                assert call(BASE+'/admin/realms/syskata/users/'+subject,token=admin)[0]==404
                assert call(endpoint,{'confirmation':'delete-account'},tokens['access_token'])[0]==403
            (ROOT/'target/identity-smoke.json').write_text(json.dumps({
                'keycloak':'26.8.2','flow':'authorization-code-pkce-S256','access_token':'accepted',
                'id_token':'rejected','disabled_token':'rejected','identity_deletion':'verified'})+'\n')
            print('PASS: real Keycloak PKCE, packaged API, immediate denial and identity deletion')
        finally:
            if api:
                api.terminate()
                try: api.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    api.kill();api.wait()
            for args in [('rm','-f',name),('network','disconnect',network,'syskata-foundation-test-db'),('network','rm',network)]:
                subprocess.run([ENGINE,*args],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


if __name__=='__main__':
    try: main()
    except Exception as failure:
        print("Failure type: " + type(failure).__name__ + "; script lines: " + ",".join(str(f.lineno) for f in traceback.extract_tb(failure.__traceback__) if f.filename == __file__))
        # Avoid printing HTTP errors, credentials, responses, callback codes or tokens.
        raise SystemExit('Identity smoke failed; inspect only sanitized service state and local logs.')
