# Security and privacy notes

## What the app does by default
- Only permission: INTERNET. No account, no analytics, no ads, no tracking SDKs.
- Likes, watch-later, history, watched lessons, notes and settings stay on this device
  (excluded from cloud backup). Export/Import is manual text you control.
- HTTPS only (cleartext blocked, system CA trust only).
- Video IDs are validated (11 chars: A-Z a-z 0-9 _ -) before use in URLs/HTML.
- Player WebView: only youtube-nocookie.com / youtube.com /embed/ may load; file and content
  access off; no JavaScript bridge into the app; no popups; mixed content blocked; Safe Browsing on;
  cookies cleared after leaving the player; playback pauses when the app is backgrounded.
- "Ask about this video" answers from the bundled HackerTube summaries and Hacker News comments.
  It makes no network requests.

## Optional on-device AI (off by default, Settings > On-device AI)
When switched on, a hidden WebView downloads a small model (about 100 MB) from Hugging Face and loads
transformers.js from jsDelivr (pinned version) the first time. Questions and video text are processed on
the phone and are not uploaded. This WebView has a small JavaScript bridge that only passes text strings.
Turning the switch off destroys it. Network endpoints involved: huggingface.co, cdn.jsdelivr.net.

## Removed in 0.3.0
Transcript scraping through YouTube's private API with spoofed app identities, forged consent cookies and
third-party Piped / noembed servers was removed: it broke YouTube's terms, was fragile, and contradicted
the privacy promise. Ollama and MediaPipe hooks were removed (dead code).

## Signing your release (do once)
    keytool -genkeypair -v -keystore release.jks -alias hackertube \
      -keyalg RSA -keysize 4096 -validity 10000
    base64 -w0 release.jks     # macOS: base64 -i release.jks
Add repo Settings > Secrets > Actions: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
Never commit the .jks; back it up offline. Without these secrets CI builds a debug-signed APK.

## Verify a download
    sha256sum -c HackerTubeLite.apk.sha256

## Repo hardening
Enable 2FA, branch protection on main, Dependabot alerts and secret scanning. Never commit API keys.

## Reporting
Open a private security advisory on the repo.
