//  Models.swift
//  The two value types mirrored from the web remote, matching ShouStore.kt /
//  Remote + Playback on Android. Decoders are lenient because the JSON comes
//  straight from the web remote's localStorage (ports can arrive as String or Int).

import Foundation

/// One saved Shou server, mirrored from the web remote's localStorage set
/// (`shou.remotes.v1`). Carries the secret REMOTE_TOKEN as `key`.
struct Remote: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var key: String
    var host: String
    var hostname: String
    var port: String
    var mac: String

    /// Best address to reach this server on right now.
    var bestHost: String { host.isEmpty ? hostname : host }

    enum CodingKeys: String, CodingKey {
        case id, name, key, host, hostname, port, mac
    }

    init(id: String, name: String, key: String, host: String,
         hostname: String, port: String, mac: String) {
        self.id = id; self.name = name; self.key = key; self.host = host
        self.hostname = hostname; self.port = port; self.mac = mac
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id       = (try? c.decode(String.self, forKey: .id)) ?? ""
        name     = (try? c.decode(String.self, forKey: .name)) ?? ""
        key      = (try? c.decode(String.self, forKey: .key)) ?? ""
        host     = (try? c.decode(String.self, forKey: .host)) ?? ""
        hostname = (try? c.decode(String.self, forKey: .hostname)) ?? ""
        mac      = (try? c.decode(String.self, forKey: .mac)) ?? ""
        port     = Remote.decodeLoose(c, .port) ?? "4100"
        if port.isEmpty { port = "4100" }
    }

    /// Accept a JSON value that may be a String ("4100") or a Number (4100).
    private static func decodeLoose(_ c: KeyedDecodingContainer<CodingKeys>,
                                    _ key: CodingKeys) -> String? {
        if let s = try? c.decode(String.self, forKey: key) { return s }
        if let i = try? c.decode(Int.self, forKey: key) { return String(i) }
        return nil
    }
}

/// A compact mirror of what the kiosk is doing, pushed from the web remote each
/// tick via `ShouNative.playback(json)`. Positions are in **seconds** (the web
/// sends seconds; Android multiplies to ms, but Swift keeps seconds throughout).
struct Playback: Codable, Equatable {
    var active: Bool       // something is playing (mpv is up)
    var playing: Bool      // true = playing, false = paused
    var title: String
    var subtitle: String
    var cover: String
    var position: Double   // seconds
    var duration: Double   // seconds

    static let idle = Playback(active: false, playing: false, title: "",
                               subtitle: "", cover: "", position: 0, duration: 0)

    enum CodingKeys: String, CodingKey {
        case active, playing, title, subtitle, cover, position, duration
    }

    init(active: Bool, playing: Bool, title: String, subtitle: String,
         cover: String, position: Double, duration: Double) {
        self.active = active; self.playing = playing; self.title = title
        self.subtitle = subtitle; self.cover = cover
        self.position = position; self.duration = duration
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        active   = (try? c.decode(Bool.self, forKey: .active)) ?? false
        playing  = (try? c.decode(Bool.self, forKey: .playing)) ?? false
        title    = (try? c.decode(String.self, forKey: .title)) ?? ""
        subtitle = (try? c.decode(String.self, forKey: .subtitle)) ?? ""
        cover    = (try? c.decode(String.self, forKey: .cover)) ?? ""
        position = (try? c.decode(Double.self, forKey: .position)) ?? 0
        duration = (try? c.decode(Double.self, forKey: .duration)) ?? 0
    }
}

/// One entry from `GET /airing` (`{shows:[{id,title,progress,available}]}`).
struct AiringShow: Codable {
    let id: Int
    let title: String
    let progress: Int
    let available: Int
}

struct AiringResponse: Codable {
    let shows: [AiringShow]
}
