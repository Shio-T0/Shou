# Shou Remote (Android)

A thin native shell around Shou's web remote: one full-screen `WebView` that
points at your Shou server and **keeps the phone screen awake** the whole time
it's open (`FLAG_KEEP_SCREEN_ON`) — no HTTPS, no video hacks, no battery-saver
fights. The web remote stays the single source of truth; this app is just a frame.

## What it does

- Loads `http://<computer>:<port>/remote?k=<token>` in a full-screen, immersive WebView.
- Forces the screen to stay on while the app is in the foreground.
- Server host / port / token / http-vs-https are set in **Settings** (stored locally).
  Reach Settings via the in-app error screen, or **long-press the launcher icon**.
- **Scan network** finds a Shou server over mDNS and fills in host + port for you
  (the token is never broadcast — you still type it once).
- Cleartext HTTP to the LAN is allowed (Shou serves plain HTTP). If you ever put
  Shou behind self-signed TLS, flip **Allow self-signed certificate** in Settings.

## Build

You need **Android Studio** (Giraffe or newer) or a local Android SDK + JDK 17.
The Gradle wrapper (8.7) is committed, so the command line works out of the box.

- **Android Studio:** `File → Open…` this `android/` folder, let it sync, then
  **Build → Build APK(s)**.
- **Command line:**
  ```sh
  cd android
  echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # point at your SDK
  ./gradlew assembleDebug          # or assembleRelease
  ```
  The APK lands in `app/build/outputs/apk/`.

`assembleRelease` signs with the **debug key** unless a release keystore is
provided (see *Releasing* below), so a local release build is still installable.

## Install & configure

1. Sideload the APK to your phone (GrapheneOS: just open the APK file).
2. Open **Shou Remote**. First launch lands on **Settings**.
3. Tap **Scan network for Shou** (or type your computer's **LAN IP** and **port**,
   default `4100`). Enter the **token** — that's `REMOTE_TOKEN` from
   `~/.config/shou/shou.conf` on the computer. Leave HTTPS off.
4. **Save & connect.** The remote loads and the screen stays awake.

## Auto-discovery (mDNS)

The Shou server advertises itself as `_shou._tcp` on the LAN (via `zeroconf`).
**Scan network** uses Android NSD to find it and auto-fill host + port. The token
is a secret and is deliberately *not* part of the advertisement.

## Releasing (CI + Obtainium)

`.github/workflows/android.yml` builds the APK and attaches it to a GitHub
Release, so you can install/auto-update with **Obtainium** (popular on
GrapheneOS) — no Play Store needed.

1. Point Obtainium at `https://github.com/Shio-T0/Shou` and let it track Releases.
2. Cut a release by pushing a tag: `git tag v1.0.0 && git push origin v1.0.0`.
   The tag drives `versionName`/`versionCode`, so each release supersedes the last.

For stable update signatures across releases (Obtainium checks the signing key),
add a release keystore via repo **Secrets** — without them the workflow falls back
to the debug key, which still produces an installable APK:

| Secret | Meaning |
|---|---|
| `SHOU_KEYSTORE_BASE64` | `base64 -w0 your.jks` of the keystore file |
| `SHOU_KEYSTORE_PASSWORD` | keystore password |
| `SHOU_KEY_ALIAS` | key alias |
| `SHOU_KEY_PASSWORD` | key password |

Generate one once with:
```sh
keytool -genkey -v -keystore shou-release.jks -alias shou \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Project layout

```
android/
  app/src/main/
    java/io/github/shiot0/shou/
      MainActivity.kt      # the WebView shell + keep-screen-on + immersive + SSL policy
      SettingsActivity.kt  # host / port / token / https / scan / self-signed toggle
    res/...                # theme, icon, settings layout, network config
  build.gradle.kts, settings.gradle.kts, ...
```

## Further ideas

- **Self-hosted Obtainium feed** if you'd rather not publish GitHub Releases.
- **Live mDNS list** in Settings (pick from multiple servers) instead of grabbing
  the first one found.
