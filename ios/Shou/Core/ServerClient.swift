//  ServerClient.swift
//  Tiny HTTP client for talking to the active Shou server from anywhere the WebView
//  isn't driving things — the lock-screen transport, the widget buttons, the
//  background airing check. Speaks the same token-gated control endpoints the web
//  remote uses (POST /pause, /next, …; GET /airing). Ported from ServerClient.kt.

import Foundation

enum ServerClient {

    /// Fire a POST control command (e.g. "pause", "next", "fwd") at the active server.
    /// Best-effort; returns true on a 2xx.
    @discardableResult
    static func command(_ path: String, params: [String: String] = [:]) async -> Bool {
        guard let base = ShouStore.activeBaseURL() else { return false }
        var query = "?k=" + enc(ShouStore.activeToken())
        for (k, v) in params { query += "&" + enc(k) + "=" + enc(v) }
        guard let url = URL(string: "\(base)/\(path)\(query)") else { return false }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = Data()
        req.timeoutInterval = 5
        do {
            let (_, resp) = try await session().data(for: req)
            return (resp as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } ?? false
        } catch {
            return false
        }
    }

    /// GET the (token-gated) /airing feed, or nil on failure.
    static func airing() async -> AiringResponse? {
        guard let base = ShouStore.activeBaseURL(),
              let url = URL(string: "\(base)/airing?k=\(enc(ShouStore.activeToken()))") else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 6
        do {
            let (data, resp) = try await session().data(for: req)
            guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
            return try? JSONDecoder().decode(AiringResponse.self, from: data)
        } catch {
            return nil
        }
    }

    // Convenience wrappers used by the media session, widget and tile.
    @discardableResult static func pause() async -> Bool { await command("pause") }
    @discardableResult static func next()  async -> Bool { await command("next") }
    @discardableResult static func prev()  async -> Bool { await command("prev") }
    @discardableResult static func fwd()   async -> Bool { await command("fwd") }
    @discardableResult static func rew()   async -> Bool { await command("rew") }
    @discardableResult static func open()  async -> Bool { await command("open") }

    // MARK: - Session (honours "Allow self-signed certificate") ----------------

    private static func session() -> URLSession {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 6
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        if ShouStore.allowBadCerts() {
            return URLSession(configuration: cfg, delegate: TrustAllDelegate.shared, delegateQueue: nil)
        }
        return URLSession(configuration: cfg)
    }

    private static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryValueAllowed) ?? s
    }
}

/// Trusts a self-signed server cert only when the user opted in (Settings → Allow
/// self-signed). Mirrors the WebView's certificate-challenge handler.
private final class TrustAllDelegate: NSObject, URLSessionDelegate {
    static let shared = TrustAllDelegate()
    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}

private extension CharacterSet {
    /// URL-query value escaping (encodes & = + and friends).
    static let urlQueryValueAllowed: CharacterSet = {
        var set = CharacterSet.urlQueryAllowed
        set.remove(charactersIn: "&=+?#")
        return set
    }()
}
