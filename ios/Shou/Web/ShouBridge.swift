//  ShouBridge.swift
//  Recreates `window.ShouNative` inside WKWebView so the *unchanged* web remote
//  (shou/static/remote.js) treats iOS exactly like the Android shell. A small JS
//  shim is injected at document start; every call is posted to this message handler
//  and dispatched to the native features. Mirrors ShouBridge.kt.
//
//  remote.js detects native by the mere existence of window.ShouNative (line 12) and
//  reads only one synchronous return — wake() → bool (line 952) — so the shim returns
//  true synchronously and does the real broadcast asynchronously; everything else is
//  fire-and-forget.

import Foundation
import WebKit
import WidgetKit

protocol ShouBridgeHost: AnyObject {
    func runJS(_ js: String)
    func setFullscreen(_ on: Bool)
}

final class ShouBridge: NSObject, WKScriptMessageHandler {

    static let messageName = "shouNative"
    weak var host: ShouBridgeHost?

    private var scanner: BonjourScanner?
    private var found: [BonjourFound] = []

    /// Injected at .atDocumentStart — defines window.ShouNative with the same method
    /// surface the Android @JavascriptInterface exposes.
    static let shimSource = """
    (function () {
      function post(method, args) {
        try {
          window.webkit.messageHandlers.shouNative.postMessage({ method: method, args: args || [] });
        } catch (e) {}
      }
      window.ShouNative = {
        version: function () { return 1; },
        syncRemotes: function (json) { post('syncRemotes', [String(json == null ? '[]' : json)]); },
        setActive: function (token, host, port, name) {
          post('setActive', [String(token||''), String(host||''), String(port||''), String(name||'')]);
        },
        playback: function (json) { post('playback', [String(json||'')]); },
        wake: function (token) { post('wake', [String(token||'')]); return true; },
        scan: function () { post('scan', []); },
        notify: function (kind, title, body) {
          post('notify', [String(kind||''), String(title||''), String(body||'')]);
        },
        fullscreen: function (on) { post('fullscreen', [!!on]); }
      };
    })();
    """

    // MARK: WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard message.name == Self.messageName,
              let body = message.body as? [String: Any],
              let method = body["method"] as? String else { return }
        let args = body["args"] as? [Any] ?? []

        switch method {
        case "syncRemotes":
            if let json = args.first as? String {
                ShouStore.setRemotes(json: json)
                Shortcuts.publish()
                reloadWidgets()
            }

        case "setActive":
            let s = args.map { "\($0)" }
            ShouStore.setActive(token: s.at(0), host: s.at(1), port: s.at(2), name: s.at(3))
            reloadWidgets()

        case "playback":
            if let json = args.first as? String { handlePlayback(json) }

        case "wake":
            let token = (args.first as? String) ?? ""
            Task { await self.doWake(token) }

        case "scan":
            startScan()

        case "notify":
            let s = args.map { "\($0)" }
            Notifications.postEvent(kind: s.at(0), title: s.at(1), body: s.at(2))

        case "fullscreen":
            let on = (args.first as? Bool) ?? false
            host?.setFullscreen(on)

        default:
            break
        }
    }

    // MARK: Handlers

    private func handlePlayback(_ json: String) {
        guard let data = json.data(using: .utf8),
              let pb = try? JSONDecoder().decode(Playback.self, from: data) else { return }
        ShouStore.setPlayback(pb.active ? pb : nil)
        NowPlayingController.shared.update(pb)
        reloadWidgets()
    }

    private func doWake(_ token: String) async {
        let mac: String
        if !token.isEmpty, let r = ShouStore.remote(byToken: token) {
            mac = r.mac
        } else {
            mac = ShouStore.activeMac()
        }
        guard !mac.isEmpty else { return }
        await Wol.wake(mac)
    }

    private func startScan() {
        found.removeAll()
        let s = BonjourScanner()
        scanner = s
        s.start(onFound: { [weak self] f in
            guard let self else { return }
            self.found.append(f)
            self.pushScan(done: false)
        }, onDone: { [weak self] in
            self?.pushScan(done: true)
            self?.scanner = nil
        })
    }

    /// Feed the cumulative result list back to window.shouOnScan(jsonString, done).
    private func pushScan(done: Bool) {
        let items = found.map { ["name": $0.name, "host": $0.host, "port": $0.port] as [String: Any] }
        guard let data = try? JSONSerialization.data(withJSONObject: items),
              let json = String(data: data, encoding: .utf8) else { return }
        // Pass the array literal and JSON.stringify it in JS, since shouOnScan parses a string.
        host?.runJS("window.shouOnScan(JSON.stringify(\(json)), \(done ? "true" : "false"));")
    }

    private func reloadWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}

private extension Array where Element == String {
    func at(_ i: Int) -> String { indices.contains(i) ? self[i] : "" }
}
