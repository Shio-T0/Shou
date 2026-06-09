# Shou Remote (iOS)

A thin native shell around Shou's web remote — the iPhone counterpart of the
[Android app](../android). One full-screen `WKWebView` points at your Shou server and
**keeps the phone screen awake** the whole time it's open (`isIdleTimerDisabled`), while
a tiny JavaScript bridge recreates `window.ShouNative` so the *same* web remote gets the
native superpowers a web page can't have: Wake-on-LAN, Bonjour discovery, lock-screen
media controls, a home-screen now-playing widget, per-server app shortcuts, and
background "new episode" notifications. The web remote stays the single source of truth;
this app is the frame plus the native muscle.

> **Built on Linux, so it ships as source.** Like the macOS server branch, this project
> can't be compiled or run from the dev machine it was written on — iOS needs a Mac with
> Xcode. Everything here is a complete, buildable Xcode project; you open it on a Mac,
> set your signing Team, and build. See **Build** below.
>
> **The default build needs no Apple payment.** It signs with a free **Personal Team**
> (just an Apple ID) and installs on your own iPhone from Xcode. The trade-off is Apple's,
> not ours: free signing **expires after 7 days** (re-run from Xcode to refresh), and three
> features that need paid-only entitlements are left out — see **Free vs full build** below.

## What it does

- Loads `http://<computer>:<port>/remote?k=<token>` in a full-screen WKWebView.
- Forces the screen to stay on while the app is in the foreground.
- Server host / port / token / HTTPS are set in **Settings** (host/port/flags in the App
  Group; the secret token in the Keychain). Reach Settings from the in-app error screen
  or the **long-press app-icon → Settings** quick action.
- **Scan network** finds a Shou server over Bonjour (`_shou._tcp`) and fills in host +
  port (the token is never broadcast — you type it once).
- Cleartext HTTP to the LAN is allowed (`NSAllowsLocalNetworking`). For a self-signed
  HTTPS Shou, flip **Allow self-signed certificate** in Settings.

## Native superpowers

All of these talk to the same token-gated server and degrade gracefully when you open the
remote in plain Safari instead:

- **Find PCs on this network.** A native Bonjour scan lists every `_shou._tcp` server it
  resolves — tap one to pre-fill the form (you still paste the key).
- **Lock-screen media controls.** `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`
  mirror what's playing with prev / play-pause / next + 30s skip, working from the Lock
  Screen, Control Center and headsets.
- **Per-server shortcuts.** Long-press the app icon for "Living room", "Bedroom", … each
  opening the remote already pointed at that PC.
- **Notifications.** A heads-up when you finish a series, and a background check
  (`BGAppRefreshTask`, polling your own `/airing`) when a Watching show gets a new
  episode — no cloud, no account.
- **Keychain-stored keys.** Saved server tokens live in the Keychain, never in plaintext.

**Full build only** (a paid account is needed to sign these — see **Free vs full build**):

- **Home-screen widget.** A `.systemMedium` **now-playing strip** — cover art,
  title/subtitle, a progress bar of what's on the PC, with **wake, prev, play/pause,
  next** inline (iOS-17 interactive widgets) — so you can drive playback without opening
  the app.
- **Control Center control (iOS 18).** A one-swipe Wake + Play/Pause **Control**, the
  closest analogue to Android's Quick Settings tile.
- **Wake-on-LAN.** Save a server's **MAC** and tap **Wake PC** to power a sleeping Shou
  box on from the couch. Magic packet, UDP broadcast — needs the multicast entitlement, so
  it only fires on the full build.

## How it differs from Android (Apple platform limits)

Everything above matches the Android app. A few things Apple simply doesn't allow an app
to do identically — here's the honest mapping, all called out in the code too:

| Android feature | iOS |
|---|---|
| **Volume rocker → PC volume** | **Omitted.** iOS forbids intercepting the volume buttons. The Settings toggle is shown but disabled; use the on-screen volume controls. |
| **Quick Settings tile** | **Control Center *Control* (iOS 18+ only).** Below iOS 18 there's no equivalent; the widget covers the same actions. |
| **Interactive widget buttons** | **Require iOS 17+** (AppIntents). The deployment floor is iOS 17 for exactly this reason. |
| **Lock-screen controls always present** | iOS only shows the Now Playing card while an audio session is active, so the app holds the slot with a *silent* looping player while the PC plays, and mixes with your other audio — so the card is best-effort and may not always promote to the foreground. |
| **Wake-on-LAN broadcast** | Works, but UDP broadcast needs the **`com.apple.developer.networking.multicast`** entitlement, which is **approved by Apple per App ID** (request it in your account). Without it, broadcasts are silently dropped. |
| **30-minute airing poll** | `BGAppRefreshTask` — **iOS decides the cadence**, so the new-episode check is best-effort, not a fixed interval. |

## Free vs full build

There are two XcodeGen specs. **The default (`project.yml`) is the free build** — because
the paid-only entitlements simply can't be signed by a free Apple ID, the features that
depend on them are left out rather than left broken.

| | **Free** (`project.yml`, default) | **Full** (`project-full.yml`) |
|---|---|---|
| Apple account | free Apple ID (**Personal Team**) | **paid** Developer Program ($99/yr) |
| Signing lifetime | **expires every 7 days** — re-run from Xcode | 1 year |
| The remote, keep-awake, lock-screen controls, Bonjour scan, shortcuts, notifications | ✅ | ✅ |
| Home-screen **widget** + iOS-18 **Control** | ❌ (needs App Group + shared Keychain) | ✅ |
| **Wake-on-LAN** | ❌ best-effort, usually dropped (needs multicast entitlement) | ✅ |
| Min iOS | 16 | 17 |
| Install for others | rebuild from Xcode (cable) | TestFlight / App Store |

The free build declares **no entitlements** at all, so there's nothing to register in an
Apple developer portal — open it, pick your Personal Team, run. The Keychain still protects
your tokens (an app's own Keychain needs no entitlement); it just isn't *shared* with a
widget, because there is no widget in the free build.

> Switching to the full build later is just `./generate.sh --full` once you have a paid
> account — no code changes; the widget/WOL/Control sources are already in the repo.

## Build

You need a **Mac with Xcode 15+** (Xcode 16 for the full build's iOS-18 Control), and
[XcodeGen](https://github.com/yonyz/XcodeGen) to turn the spec into an Xcode project (the
`.xcodeproj` is intentionally *not* committed — the `project*.yml` are the source of truth,
mirroring how `android/` commits the Gradle wrapper rather than IDE files).

```sh
cd ios
brew install xcodegen      # one-off
./generate.sh              # FREE build (default) — no Apple payment needed
# ./generate.sh --full     # FULL build — paid account
open Shou.xcodeproj
```

Then in Xcode:

1. **Signing & Capabilities → Team:** pick your team. For the free build that's your
   **Personal Team** (it appears once you've added your Apple ID in Xcode → Settings →
   Accounts). Nothing else to configure — the free build has no capabilities to enable.
2. **Full build only:** on *both* the `Shou` and `ShouWidget` targets, register in the dev
   portal the **App Group** `group.io.github.shiot0.shou`, a **Keychain access group**, and
   the **Multicast Networking** entitlement (request approval); the entitlements files
   already declare them.
3. Plug in your iPhone, select it as the run destination, and **Run** (Bonjour and a real
   server on the LAN want actual hardware, not the Simulator). On a free Personal Team the
   app stops launching after 7 days — just Run again from Xcode to re-sign it.

## Configure

1. Open **Shou Remote**. With nothing set up it lands on the error screen → **Settings**.
2. Tap **Scan network for Shou** (or type your computer's **LAN IP** and **port**, default
   `4100`). Enter the **token** — that's `REMOTE_TOKEN` from `~/.config/shou/shou.conf` on
   the computer. Leave HTTPS off.
3. **Save & connect.** The remote loads and the screen stays awake.

First launch also prompts for **Local Network** access (Bonjour/LAN) and **Notifications** —
both are needed for discovery and the new-episode heads-up.

## Distribution

iOS has no Obtainium/sideload-style channel like the Android build. Options:

- **Free build (default)** — install on your own iPhone straight from Xcode (cable). It's
  re-signed each time you Run, and the install lapses after 7 days on a free account, so
  this is "keep it on my own phone," not "share it around."
- **TestFlight / App Store** *(full build)* — needs a **paid Apple Developer account** and
  App Store Connect; archive in Xcode (or wire up CI on a macOS runner later) and upload.
- **Direct install** *(full build)* — a development or ad-hoc build to registered devices.

There's deliberately **no CI here yet** (the dev box is Linux); add a macOS-runner workflow
+ fastlane when you want automated TestFlight builds.

## Project layout

```
ios/
  project.yml                 # XcodeGen — FREE build (default): app only, no entitlements
  project-full.yml            # XcodeGen — FULL build (paid): app + widget + entitlements
  generate.sh                 # `xcodegen generate` helper (--full for the paid build)
  Shou/
    App/
      AppDelegate.swift        # UIKit lifecycle, orientation lock, BG task register
      SceneDelegate.swift      # window + root shell, quick-action routing
    Web/
      WebShellController.swift  # WKWebView shell: keep-awake, TLS, error page, fullscreen
      ShouBridge.swift          # window.ShouNative shim + WKScriptMessageHandler
    Core/                       # shared with the widget extension (full build)
      Models.swift              # Remote, Playback, AiringShow
      ShouStore.swift           # Keychain + (App Group on full / app defaults on free)
      ServerClient.swift        # token-gated control + /airing
      Wol.swift                 # Wake-on-LAN magic packet (NWConnection)
      ArtLoader.swift           # async cover cache
      Theme.swift               # the Brand palette (colors.xml ported)
    Net/
      BonjourScanner.swift      # _shou._tcp discovery (NetService)
    Media/
      NowPlayingController.swift # lock-screen controls + silent-session trick
    Settings/
      SettingsView.swift        # the connection screen (+ hosting controller)
    System/
      Notifications.swift       # finished / airing local notifications
      AiringTask.swift          # BGAppRefreshTask new-episode poll
      Shortcuts.swift           # dynamic per-server app-icon shortcuts
    Resources/
      Info.plist, Shou.entitlements, Assets.xcassets (AppIcon, colors)
  ShouWidget/                   # full build only (paid) — omitted from the free build
    ShouWidgetBundle.swift      # @main bundle (widget + iOS-18 controls)
    ShouWidget.swift            # the now-playing strip (systemMedium)
    WidgetIntents.swift         # Wake / Pause / Prev / Next AppIntents
    ShouControl.swift           # iOS-18 Control Center controls
    Info.plist, ShouWidget.entitlements, Assets.xcassets
```

## Further ideas

- **Live Activity** on the Lock Screen / Dynamic Island for the current episode.
- **Native re-skin** of the remote in SwiftUI (today it's the shared web remote, by design).
- **CI on a macOS runner** + fastlane for hands-off TestFlight builds.
