#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres >/dev/null <<'SQL'
\getenv app_password SYSKATA_DB_PASSWORD
\getenv evidence_password SYSKATA_EVIDENCE_PASSWORD
\getenv identity_password KEYCLOAK_DB_PASSWORD
CREATE USER syskata PASSWORD :'app_password';
CREATE USER syskata_evidence PASSWORD :'evidence_password';
CREATE USER keycloak PASSWORD :'identity_password';
CREATE DATABASE syskata OWNER syskata;
CREATE DATABASE syskata_evidence OWNER syskata_evidence;
CREATE DATABASE keycloak OWNER keycloak;
REVOKE CONNECT ON DATABASE syskata FROM PUBLIC;
REVOKE CONNECT ON DATABASE syskata_evidence FROM PUBLIC;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE syskata TO syskata;
GRANT CONNECT ON DATABASE syskata_evidence TO syskata_evidence;
GRANT CONNECT ON DATABASE keycloak TO keycloak;
SQL
