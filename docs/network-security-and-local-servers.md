# Network Security & Local Server Support

Status: accepted (2026-09-24)
Scope: `androidApp/src/main/res/xml/network_security_config.xml`, `iosApp/iosApp/Info.plist`, server connection code in `lib/network` and `lib/server-*`

## Decision

Parrot permits **cleartext HTTP connections to arbitrary hosts, including release builds**. This is a deliberate product requirement, not a leftover development setting.

## Why

Parrot connects to user-configured servers:

- **Storyteller** self-hosted instances (default `http://<host>:8000`)
- **Audiobookshelf** servers
- Other LAN-only deployments on `http://192.168.x.x:<port>`

These hosts are entered by the user at runtime and may be:

- plain HTTP on the local network (no TLS certificate available),
- addressed by raw IP or a `.local` name (no public DNS, no ACME),
- reachable only inside the user's home network.

Android's `network-security-config` `domain-config` rules require an **enumerable domain list**, which is impossible for user-entered server URLs. Therefore `base-config cleartextTrafficPermitted="true"` is the only workable configuration. This matches other self-hosted media clients (Jellyfin, Navidrome, Audiobookshelf's own app).

## Current configuration

### Android

`androidApp/src/main/res/xml/network_security_config.xml`:

```xml
<base-config cleartextTrafficPermitted="true" />
```

Wired via `android:networkSecurityConfig` in `androidApp/src/main/AndroidManifest.xml`.

**Do not "fix" this** by setting `cleartextTrafficPermitted="false"` or moving it to `src/debug` — it will break every local-server user.

### iOS

`iosApp/iosApp/Info.plist` currently has **no `NSAppTransportSecurity` (ATS) exception**. ATS blocks plain HTTP by default, so iOS behavior is *stricter* than Android today:

- `https://` servers: work on both platforms.
- `http://` LAN servers: work on Android, **blocked on iOS** (Ktor's Darwin engine goes through `URLSession`, which enforces ATS).

If `http://` LAN servers must work on iOS, add one of the following to `Info.plist`:

```xml
<!-- Covers unqualified hostnames and *.local only -->
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

Raw-IP targets (`http://192.168.1.5:8000`) are **not** covered by `NSAllowsLocalNetworking`; matching Android's posture for those requires `NSAllowsArbitraryLoads = true`. Decide explicitly which posture iOS should have and record it here.

## Accepted risks and mitigations

| Risk | Mitigation |
|---|---|
| Bearer tokens / credentials transit in plaintext on the LAN | Users choose the server URL; document the exposure in the connection UI ("unencrypted connection" hint for `http://` URLs) — recommended, not yet implemented |
| MITM of HTTP traffic | Only applies to user-chosen HTTP endpoints. Fixed endpoints (Supabase, Firebase) are HTTPS-only and could additionally be certificate-pinned |
| Config drift | The XML comment in `network_security_config.xml` must state the LAN requirement, not "development only" |
| TLS bypass creep | No trust-all `TrustManager`, `HostnameVerifier` bypass, or `verify = false` exists in the codebase. **Keep it that way** — cleartext permission is the only intentional exception |

## Rules for future changes

1. **Never introduce a trust-all TLS configuration** to "support self-signed certs". If self-signed support is needed, implement per-server certificate trust chosen by the user.
2. Do not remove cleartext support without a migration path for LAN users.
3. If adding new fixed service endpoints (cloud, sync), they must be HTTPS-only — cleartext exists for *user-hosted* servers only.
4. Any change to ATS policy on iOS must keep parity with the Android behavior described here.

## References

- Android network security config: https://developer.android.com/privacy-and-security/security-config
- iOS ATS: https://developer.apple.com/documentation/bundleresources/information_property_list/nsapptransportsecurity
