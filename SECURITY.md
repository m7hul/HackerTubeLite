# Security notes

## What the app does
- Only permission: INTERNET. No login, no accounts, no analytics, no ad SDKs.
- Likes are stored on-device only (excluded from cloud backup).
- HTTPS only (cleartext blocked, system CA trust only).
- Video IDs are validated (11 chars, A-Z a-z 0-9 _ -) before use in URLs/HTML.
- WebView: only youtube-nocookie.com / youtube.com /embed/ may load; file and
  content access off; no JS bridge; no popups; mixed content blocked; Safe
  Browsing on; third-party cookies off; cookies cleared on leaving the player.
- Release builds: R8 minify, non-debuggable.

## Signing your release (do once)
    keytool -genkeypair -v -keystore release.jks -alias hackertube \
      -keyalg RSA -keysize 4096 -validity 10000
    base64 -w0 release.jks     # macOS: base64 -i release.jks
Add repo Settings > Secrets > Actions: KEYSTORE_BASE64, KEYSTORE_PASSWORD,
KEY_ALIAS, KEY_PASSWORD. Never commit the .jks; back it up offline.
Without these secrets, CI builds a debug-signed APK (testing only).

## Verify a download
    sha256sum -c HackerTubeLite.apk.sha256

## Repo hardening (GitHub settings)
Enable 2FA, branch protection on main, Dependabot alerts, secret scanning.
Consider pinning GitHub Actions to full commit SHAs.

## Reporting
Open a private security advisory on the repo.
