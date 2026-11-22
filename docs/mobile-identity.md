# Mobile identity and changing environments

Companion to [app #3](https://github.com/SysKata-project/app/issues/3).
The owner approved first-install server configuration and the fixed native return
URI on 2026-11-22. A stable public domain, cluster or store release is not assumed.

## Reuse the existing service

`dev/compose.yaml` already provides Keycloak 26.8.2 with persistent PostgreSQL
storage and the imported `syskata` realm. The public `syskata-mobile` client uses
Code/PKCE S256 and issues tokens for `syskata-api`. The HTTPS overlay adds TLS to
this service; it does not create a second identity database or provision a cluster.

1. Prepare the existing ignored `.env` as described in
   [local setup](service-foundation.md). Add the settings from
   [`mobile.env.example`](../dev/mobile.env.example), replacing placeholder paths
   and hostnames. Never commit the populated file or certificate private key.
2. Supply `tls.crt` (PEM certificate chain) and `tls.key` in the configured private
   directory, readable by the container's runtime user. The certificate must
   match the chosen hostname and be trusted by the phone and API host. For a
   disposable private CA, install trust explicitly on test devices. Do not
   disable certificate verification in the app or API.
3. Use a hostname reachable from the phone. The default bind is loopback;
   set `KEYCLOAK_HTTPS_BIND` to an appropriate host interface for a controlled
   test network. `localhost` on a physical phone refers to that phone.
4. Start with Compose v2 (or a compatible Podman Compose provider):

   ```sh
   docker compose --env-file .env -f dev/compose.yaml -f dev/compose.https.yaml up -d
   ```

   The overlay disables Keycloak HTTP. The inherited local HTTP port mapping
   has no HTTP listener. The HTTPS origin must include the externally used port.
   Preserve the same Compose project name when using existing database volumes.
5. Set the API's `SYSKATA_OIDC_ISSUER` to the exact new HTTPS realm URL and trust
   its certificate chain. Existing tokens from another issuer are invalid.
6. Check `https://HOST:PORT/realms/syskata/.well-known/openid-configuration` over
   verified TLS. Its `issuer` must match exactly and its authorization, token
   and userinfo endpoints must be HTTPS. Check API authentication with the same
   issuer; a responding login page alone does not validate the integration.

For a cluster using an OpenShift Route with edge TLS termination, configure
Keycloak's exact external HTTPS hostname and trusted proxy headers; enable its
internal HTTP listener only behind that controlled proxy. Do not expose the
management interface or trust client-supplied forwarded headers. Full cluster
installation and scoped networking remain [docs #5](https://github.com/SysKata-project/docs/issues/5).
The Compose overlay above uses direct TLS and does not configure proxy headers.

## Register the mobile callback

New realm imports include both the existing local test callback and the exact
mobile callback `org.syskata.app:/oauth2redirect`. Keycloak startup imports do
not update an existing realm. For an existing installation, an administrator
must add that exact URI to `syskata-mobile` → Valid redirect URIs, preserving
public-client Code flow and required PKCE S256. No wildcard and no client secret.
The callback returns to the app through its OS registration; it is not a
Keycloak endpoint or a domain that changes with the cluster.

On first launch, the live mobile build asks for the HTTPS API origin and complete
Keycloak realm URL. Sign out to change them. The app uses system-browser sign-in,
secure refresh-token storage, and a fresh login prompt for account selection.
Sign-out clears local credentials and recovery data and attempts refresh-token
revocation through HTTPS POST. It does not claim to clear browser-wide SSO cookies;
failed revocation is reported and existing short-lived access tokens expire under
the accepted Keycloak policy. No token-bearing browser logout URL is constructed.

## Validation limits

This configuration is reusable setup, not evidence that a particular hostname,
certificate, cluster or native-device login has been validated. Record actual TLS
and device results with the app's validation evidence. No certificate trust store,
existing realm or running deployment is changed by committing these files.

References: [Keycloak TLS](https://www.keycloak.org/server/enabletls),
[hostname](https://www.keycloak.org/server/hostname),
[reverse proxy](https://www.keycloak.org/server/reverseproxy), and
[native OAuth redirects](https://www.rfc-editor.org/rfc/rfc8252.html).
