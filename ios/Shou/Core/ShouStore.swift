//  ShouStore.swift
//  The native source of truth shared by every entry point (WebView bridge, media
//  session, widget, AppIntents, Wake-on-LAN, shortcuts, background airing). Ported
//  from ShouStore.kt. Two backing stores:
//
//   * the Keychain (shared via the app's keychain-access-group) for anything secret
//     — the saved remotes (which carry REMOTE_TOKEN keys), the single-server token,
//     and the active token; reachable from the widget process too.
//   * an App Group UserDefaults for the non-secret bits the widget/intents read to
//     render fast — active host/port/name + the last playback snapshot.

import Foundation
import Security

enum ShouStore {

    static let appGroup = "group.io.github.shiot0.shou"
    private static let keychainService = "io.github.shiot0.shou"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    // MARK: - Single-server settings (Settings screen) ----------------------- //

    static func host() -> String { defaults.string(forKey: "host")?.trimmed ?? "" }
    static func port() -> String {
        let p = defaults.string(forKey: "port")?.trimmed ?? "4100"
        return p.isEmpty ? "4100" : p
    }
    static func https() -> Bool { defaults.bool(forKey: "https") }
    static func allowBadCerts() -> Bool { defaults.bool(forKey: "allowBadCerts") }
    /// Present for Settings parity with Android; inert on iOS (no volume-key API).
    static func volumeKeys() -> Bool { defaults.object(forKey: "volumeKeys") as? Bool ?? false }

    static func token() -> String { Keychain.get("token") ?? "" }

    static func saveSettings(host: String, port: String, token: String,
                             https: Bool, allowBadCerts: Bool, volumeKeys: Bool = false) {
        let d = defaults
        d.set(host.trimmed, forKey: "host")
        let p = port.trimmed
        d.set(p.isEmpty ? "4100" : p, forKey: "port")
        d.set(https, forKey: "https")
        d.set(allowBadCerts, forKey: "allowBadCerts")
        d.set(volumeKeys, forKey: "volumeKeys")
        Keychain.set("token", token.trimmed)
    }

    // MARK: - Saved remotes (synced from the web remote's localStorage set) --- //

    static func setRemotes(json: String) {
        guard let data = json.data(using: .utf8),
              let list = try? JSONDecoder().decode([Remote].self, from: data) else { return }
        let cleaned = list.filter { !$0.key.isEmpty }
        guard let out = try? JSONEncoder().encode(cleaned),
              let str = String(data: out, encoding: .utf8) else { return }
        Keychain.set("remotes", str)
    }

    static func remotes() -> [Remote] {
        guard let raw = Keychain.get("remotes"), let data = raw.data(using: .utf8),
              let list = try? JSONDecoder().decode([Remote].self, from: data) else { return [] }
        return list
    }

    static func remote(byToken token: String) -> Remote? {
        remotes().first { $0.key == token }
    }

    // MARK: - Active endpoint (what background features talk to) -------------- //

    static func setActive(token: String, host: String, port: String, name: String) {
        Keychain.set("active_token", token)
        let d = defaults
        d.set(host, forKey: "active_host")
        d.set(port.isEmpty ? "4100" : port, forKey: "active_port")
        d.set(name, forKey: "active_name")
    }

    static func activeToken() -> String {
        let t = Keychain.get("active_token") ?? ""
        return t.isEmpty ? token() : t
    }
    static func activeHost() -> String {
        let h = defaults.string(forKey: "active_host") ?? ""
        return h.isEmpty ? host() : h
    }
    static func activePort() -> String {
        let p = defaults.string(forKey: "active_port") ?? ""
        return p.isEmpty ? port() : p
    }
    static func activeName() -> String {
        let n = defaults.string(forKey: "active_name") ?? ""
        if !n.isEmpty { return n }
        let h = activeHost()
        return h.isEmpty ? "Shou" : h
    }

    /// Base http(s) URL for the active server, or nil if nothing is configured yet.
    static func activeBaseURL() -> String? {
        let h = activeHost()
        guard !h.isEmpty else { return nil }
        let scheme = https() ? "https" : "http"
        return "\(scheme)://\(h):\(activePort())"
    }

    /// MAC for Wake-on-LAN of the active server (from its saved remote), or "".
    static func activeMac() -> String {
        remote(byToken: activeToken())?.mac.trimmed ?? ""
    }

    // MARK: - Playback snapshot (App Group — read by widget & intents) -------- //

    static func setPlayback(_ p: Playback?) {
        let d = defaults
        guard let p = p, p.active else {
            d.set(false, forKey: "pb_active"); return
        }
        d.set(true, forKey: "pb_active")
        d.set(p.playing, forKey: "pb_playing")
        d.set(p.title, forKey: "pb_title")
        d.set(p.subtitle, forKey: "pb_subtitle")
        d.set(p.cover, forKey: "pb_cover")
        d.set(p.position, forKey: "pb_pos")
        d.set(p.duration, forKey: "pb_dur")
        d.set(Date().timeIntervalSince1970, forKey: "pb_stamp")
    }

    static func playback() -> Playback? {
        let d = defaults
        guard d.bool(forKey: "pb_active") else { return nil }
        return Playback(
            active: true,
            playing: d.bool(forKey: "pb_playing"),
            title: d.string(forKey: "pb_title") ?? "",
            subtitle: d.string(forKey: "pb_subtitle") ?? "",
            cover: d.string(forKey: "pb_cover") ?? "",
            position: d.double(forKey: "pb_pos"),
            duration: d.double(forKey: "pb_dur")
        )
    }

    /// Wall-clock seconds since the snapshot was written (used to extrapolate the
    /// widget progress bar between timeline refreshes).
    static func playbackAge() -> TimeInterval {
        let stamp = defaults.double(forKey: "pb_stamp")
        return stamp > 0 ? Date().timeIntervalSince1970 - stamp : 0
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

// MARK: - Minimal shared-Keychain wrapper -------------------------------------- //
// No explicit kSecAttrAccessGroup: both the app and the widget declare the same
// single keychain-access-group, so items default into it and are shared.

enum Keychain {
    private static let service = "io.github.shiot0.shou"

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ account: String, _ value: String) {
        let data = Data(value.utf8)
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if SecItemCopyMatching(base as CFDictionary, nil) == errSecSuccess {
            SecItemUpdate(base as CFDictionary,
                          [kSecValueData as String: data] as CFDictionary)
        } else {
            var add = base
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    static func delete(_ account: String) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(q as CFDictionary)
    }
}
