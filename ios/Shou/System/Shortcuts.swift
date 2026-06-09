//  Shortcuts.swift
//  Per-server Home-Screen quick actions (long-press the app icon) — the iOS counterpart
//  of Shortcuts.kt: the top saved remotes each open the app pointed at that PC, plus a
//  static "Settings" action. Refreshed whenever the web remote syncs its server set.

import UIKit

enum Shortcuts {

    static let remoteType = "io.github.shiot0.shou.remote"
    static let settingsType = "io.github.shiot0.shou.settings"

    /// Rebuild the dynamic shortcut list from the saved remotes (max 4 slots total,
    /// so the top 3 servers + Settings).
    static func publish() {
        var items: [UIApplicationShortcutItem] = []
        for r in ShouStore.remotes().prefix(3) where !r.key.isEmpty {
            let name = r.name.isEmpty ? (r.bestHost.isEmpty ? "Shou" : r.bestHost) : r.name
            items.append(UIApplicationShortcutItem(
                type: remoteType,
                localizedTitle: String(name.prefix(25)),
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: "play.rectangle.on.rectangle"),
                userInfo: ["token": r.key as NSString]
            ))
        }
        items.append(UIApplicationShortcutItem(
            type: settingsType,
            localizedTitle: "Settings",
            localizedSubtitle: nil,
            icon: UIApplicationShortcutIcon(systemImageName: "gearshape"),
            userInfo: nil
        ))
        DispatchQueue.main.async {
            UIApplication.shared.shortcutItems = items
        }
    }
}
