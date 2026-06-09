# Shou Remote (iOS)

The iPhone counterpart of the [Android app](../android): a thin **WKWebView shell** around
Shou's web remote that **keeps the screen awake**, plus the native tricks a web page can't —
Bonjour discovery, lock-screen controls, per-server shortcuts, new-episode notifications,
and (on the paid build) a now-playing home-screen widget + Wake-on-LAN. The web remote stays
the single source of truth; this is just the frame.

> **No App Store, no payment — by default.** iOS only builds on a **Mac with Xcode**, but the
> default build signs with a **free Apple ID** (a "Personal Team") and installs straight onto
> your own iPhone. Apple's catch: free signing **expires every 7 days**, so you re-run it from
> Xcode now and then. Such is life outside the walled garden.

## Build & install

```sh
cd ios
brew install xcodegen      # one-off — turns project.yml into an Xcode project
./generate.sh              # FREE build (default).  --full for the paid build
open Shou.xcodeproj
```

In Xcode: **Signing → Team** → pick your Personal Team (it appears once your Apple ID is in
Xcode → Settings → Accounts). Plug in your iPhone, hit **Run**. That's it — the free build
declares no special capabilities, so there's nothing to register in a developer portal.

Then open the app → **Settings**, **Scan network for Shou** (or type the LAN IP + port
`4100`), paste your **token** (`REMOTE_TOKEN` from `~/.config/shou/shou.conf`), and **Save**.

## Free vs full build

| | **Free** (`project.yml`, default) | **Full** (`project-full.yml`) |
|---|---|---|
| Account | free Apple ID | **paid** Developer Program ($99/yr) |
| Signing lasts | **7 days** — re-run from Xcode | 1 year |
| Remote, keep-awake, lock-screen, Bonjour, shortcuts, notifications | ✅ | ✅ |
| Home-screen **widget** + iOS-18 **Control** | ❌ (needs paid entitlements) | ✅ |
| **Wake-on-LAN** | ❌ (needs the multicast entitlement) | ✅ |

Switching later is just `./generate.sh --full` once you have a paid account — no code
changes; the widget/WOL sources are already in the repo.

## What Apple won't let it match

A few Android features can't be identical, and we leave them out rather than fake them:
**volume-button capture** is omitted (iOS forbids it — use the on-screen controls), the
**Quick Settings tile** becomes an **iOS-18 Control Center control**, and the lock-screen
card is **best-effort** (iOS only shows it while an audio session is live, so the app holds
the slot with a silent player). All flagged in the code too.

## Layout

`project.yml` (free) / `project-full.yml` (paid) drive XcodeGen; `Shou/` is the app
(`Web/` shell + bridge, `Core/` shared logic, `Media/`, `Net/`, `System/`, `Settings/`),
`ShouWidget/` is the full-build-only widget + Control. The `.xcodeproj` isn't committed —
the specs are the source of truth, the way `android/` commits the Gradle wrapper, not IDE files.
