# Shou Remote (Android)

A thin native shell around Shou's web remote: one full-screen WebView that **keeps the
phone screen awake** the whole time it's open — no HTTPS, no battery-saver fights. The web
remote stays the single source of truth; this app just adds the native tricks a web page
can't: **Wake-on-LAN**, **mDNS discovery**, **lock-screen controls**, a now-playing
**home-screen widget + Quick Settings tile**, **per-server shortcuts**, and **new-episode
notifications**. Open the remote in a plain browser instead and it all degrades gracefully.

## Install

1. **Get the APK** from [Releases](../../releases/latest) and open it (GrapheneOS installs
   it directly), **or** track the repo in
   [Obtainium](https://github.com/ImranR98/Obtainium) (`https://github.com/Shio-T0/Shou`)
   for one-tap auto-updates.
2. Open **Settings → Scan network for Shou** — mDNS finds the PC and fills in host + port.
   (No mDNS? Just type the LAN IP and port `4100`.)
3. Paste your **token** — `REMOTE_TOKEN` from `~/.config/shou/shou.conf` — and **Save**. The
   remote loads and the screen stays awake.

## Build it yourself

Needs **Android Studio** or a local SDK + JDK 17; the Gradle wrapper is committed, so the
command line works out of the box.

```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # point at your SDK
./gradlew assembleDebug                                # or assembleRelease
```

The APK lands in `app/build/outputs/apk/`. `assembleRelease` signs with the debug key
unless you supply a release keystore (CI reads one from repo secrets — see
`.github/workflows/android.yml`), so a local release build still installs.

> Tag a release (`git tag v1.0.0 && git push origin v1.0.0`) and CI builds the APK and
> attaches it to a GitHub Release for Obtainium — because shipping should be one `git push`,
> not a ritual.
