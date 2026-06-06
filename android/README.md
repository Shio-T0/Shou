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
- Cleartext HTTP to the LAN is allowed (Shou serves plain HTTP).

## Build

You need **Android Studio** (Giraffe or newer) or a local Android SDK + JDK 17.

The Gradle wrapper **jar** is intentionally not committed (it's a binary). So either:

- **Android Studio:** `File → Open…` this `android/` folder and let it sync — it
  provisions the wrapper and SDK automatically. Then **Build → Build APK(s)**.
- **Command line:** generate the wrapper once, then build:
  ```sh
  cd android
  gradle wrapper --gradle-version 8.7   # only needed the first time
  ./gradlew assembleRelease             # or assembleDebug
  ```
  The APK lands in `app/build/outputs/apk/`.

For a real (signed) release build, set up a keystore and `signingConfigs` in
`app/build.gradle.kts`; the debug APK is fine for personal sideloading.

## Install & configure

1. Sideload the APK to your phone (GrapheneOS: just open the APK file).
2. Open **Shou Remote**. First launch lands on **Settings**.
3. Enter your computer's **LAN IP** (e.g. `192.168.1.20`), the **port** (default
   `4100`), and the **token** — that's `REMOTE_TOKEN` from
   `~/.config/shou/shou.conf` on the computer. Leave HTTPS off.
4. **Save & connect.** The remote loads and the screen stays awake.

## Project layout

```
android/
  app/src/main/
    java/io/github/shiot0/shou/
      MainActivity.kt      # the WebView shell + keep-screen-on + immersive mode
      SettingsActivity.kt  # host / port / token / https form
    res/...                # theme, icon, settings layout, network config
  build.gradle.kts, settings.gradle.kts, ...
```

## Ideas for later

- **Distribution:** build the APK in CI, attach to a GitHub Release, and install/
  auto-update via **Obtainium** (popular on GrapheneOS) — no Play Store needed.
- **Auto-discovery:** advertise the server over mDNS/zeroconf so the app finds the
  computer without typing an IP.
- **Self-signed HTTPS:** add an "accept this cert" toggle if you ever serve Shou
  over TLS (then the real Screen Wake Lock API works too).
