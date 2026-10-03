# Shou Remote (Android)

A native Android remote for Shou, built with Jetpack Compose. It talks to the same server
as the web remote (live state over Socket.IO, control over the token-gated endpoints), so
the PC stays the single source of truth, but everything on the phone is native:

- **Follows the kiosk.** Browsing, playing, rating, search and show details each get their
  own layout, lit by the focused show's own artwork and colour.
- **Your whole list as a poster grid.** Tap a poster and the PC jumps to it; the *Up next*
  bar above the tabs plays it. Continue Watching sits on top as wide art cards (long-press
  to remove one). While an episode plays, that bar becomes a mini player.
- **Now playing:** drag the timeline to seek the PC, ±15 s / ±30 s, *Skip opening* (+1:25),
  previous/next episode, PC volume (the phone's volume buttons work too).
- **Watch on phone:** the PC's episode continues in a native full-screen player (Media3);
  *Back to the PC* resumes it on the big screen from the same spot.
- **Search with your own keyboard** (the Watching / Planned / Search tabs stay right above
  it), filter by genre/theme, open a show to read its synopsis, flip between seasons and set
  its list status.
- **Sign in to AniList from the phone:** Settings → AniList opens AniList's own login; tap
  *Authorize* and the PC is signed in (and shows that account's lists), no terminal needed.
- **Several PCs:** switch from the top bar; Shou re-finds each one on whatever network you're
  on, and can **wake** it (Wake-on-LAN) or **find** it (mDNS).
- Keeps the screen awake while open, plus **lock-screen controls**, a now-playing
  **home-screen widget + Quick Settings tile**, **per-server shortcuts**, and
  **new-episode notifications**.

The web remote at `/remote` still works in any phone browser (and the iOS app).

## Install

1. **Get the APK** from [Releases](../../releases/latest) and open it (GrapheneOS installs
   it directly), **or** track the repo in
   [Obtainium](https://github.com/ImranR98/Obtainium) (`https://github.com/Shio-T0/Shou`)
   for one-tap auto-updates.
2. Open it and tap **Find PCs on this network** — mDNS finds the PC and fills in its address.
   (No mDNS? Choose **Enter the address myself** and type the LAN IP and port `4100`.)
3. Paste your **key** — `REMOTE_TOKEN` from `~/.config/shou/shou.conf` — and **Save and
   connect**. Quicker still: paste the whole phone link `install.sh` printed
   (`http://<host>:4100/remote?k=…`) into Address and everything fills itself in.

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
