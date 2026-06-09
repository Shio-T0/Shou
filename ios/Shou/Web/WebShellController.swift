//  WebShellController.swift
//  The native shell around the Shou web remote — the iOS counterpart of MainActivity.kt.
//  A single full-screen WKWebView that never lets the screen sleep, runs edge-to-edge,
//  points at the server URL from Settings, and bridges the web remote to native
//  superpowers via ShouBridge. The web remote stays the single source of truth.

import UIKit
import WebKit

final class WebShellController: UIViewController, WKNavigationDelegate, WKUIDelegate, ShouBridgeHost {

    private var webView: WKWebView!
    private let bridge = ShouBridge()
    private var loadedURL: String?

    // MARK: Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Brand.inkUI

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []   // autoplay (cast player)
        config.websiteDataStore = .default()

        bridge.host = self
        let ucc = WKUserContentController()
        ucc.add(bridge, name: ShouBridge.messageName)
        ucc.addUserScript(WKUserScript(source: ShouBridge.shimSource,
                                       injectionTime: .atDocumentStart,
                                       forMainFrameOnly: true))
        config.userContentController = ucc

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = Brand.inkUI
        webView.scrollView.backgroundColor = Brand.inkUI
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = true
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(webView)

        load()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        UIApplication.shared.isIdleTimerDisabled = true   // keep the phone screen awake
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// Reload if Settings changed the server URL while we were away.
    func reloadIfChanged() {
        if let url = buildURL(), url != loadedURL { load() }
    }

    // Edge-to-edge: hide the status bar + home indicator so the WebView reaches every
    // screen edge (the iOS analogue of the Android immersive cutout window).
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }

    // MARK: URL building / loading (mirrors MainActivity.buildUrl/load)

    private func buildURL() -> String? {
        let host = ShouStore.activeHost()
        guard !host.isEmpty else { return nil }
        let scheme = ShouStore.https() ? "https" : "http"
        let port = ShouStore.activePort()
        let token = ShouStore.activeToken()
        let query = token.isEmpty ? "" :
            "?k=" + (token.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? token)
        return "\(scheme)://\(host):\(port)/remote\(query)"
    }

    private func load() {
        guard let urlString = buildURL(), let url = URL(string: urlString) else {
            presentSettings()
            return
        }
        loadedURL = urlString
        webView.load(URLRequest(url: url))
    }

    /// A shortcut/widget may target a specific saved server; make it active and reload.
    func switchToServer(token: String) {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let r = ShouStore.remote(byToken: t) else { return }
        ShouStore.setActive(token: r.key, host: r.bestHost, port: r.port, name: r.name)
        load()
    }

    func presentSettings() {
        guard presentedViewController == nil else { return }
        let host = SettingsHostingController { [weak self] in
            self?.dismiss(animated: true) { self?.reloadIfChanged() }
        }
        host.modalPresentationStyle = .formSheet
        present(host, animated: true)
    }

    // MARK: ShouBridgeHost

    func runJS(_ js: String) {
        DispatchQueue.main.async { [weak self] in
            self?.webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    func setFullscreen(_ on: Bool) {
        DispatchQueue.main.async {
            AppDelegate.orientationLock = on ? .landscape : .allButUpsideDown
            if on {
                // Nudge the system to re-evaluate supported orientations.
                if #available(iOS 16.0, *) {
                    self.setNeedsUpdateOfSupportedInterfaceOrientations()
                    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                    scenes.first?.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape))
                } else {
                    UIDevice.current.setValue(UIInterfaceOrientation.landscapeRight.rawValue, forKey: "orientation")
                }
            }
        }
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.allow); return }

        // Internal error-page links (the native "Can't reach Shou" page).
        if url.scheme == "app" {
            switch url.host {
            case "settings": presentSettings()
            case "retry":    load()
            default:         break
            }
            decisionHandler(.cancel)
            return
        }

        // Shou pages stay in the WebView; genuine external links open in Safari.
        if let scheme = url.scheme, scheme == "http" || scheme == "https" {
            if isInternalHost(url.host) { decisionHandler(.allow); return }
        }
        if navigationAction.navigationType == .linkActivated || url.scheme != "http" && url.scheme != "https" {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    /// Keep navigations to Shou servers in-app: the active host, any saved remote, or a
    /// private LAN / *.local address (so switching between remotes doesn't bounce to Safari).
    private func isInternalHost(_ host: String?) -> Bool {
        guard let h = host?.lowercased(), !h.isEmpty else { return false }
        if h == ShouStore.activeHost().lowercased() { return true }
        for r in ShouStore.remotes() where !r.key.isEmpty {
            if h == r.host.lowercased() || h == r.hostname.lowercased() { return true }
        }
        if h.hasSuffix(".local") || h == "localhost" { return true }
        if h.hasPrefix("192.168.") || h.hasPrefix("10.") || h.hasPrefix("127.") { return true }
        if h.hasPrefix("172.") {
            let parts = h.split(separator: ".")
            if parts.count > 1, let second = Int(parts[1]), (16...31).contains(second) { return true }
        }
        return false
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        showError()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        showError()
    }

    func webView(_ webView: WKWebView,
                 didReceive challenge: URLAuthenticationChallenge,
                 completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        // Self-signed Shou over TLS: trust only if the user opted in, else refuse + explain.
        if ShouStore.allowBadCerts() {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            showError("This server's HTTPS certificate isn't trusted. If it's your own Shou "
                + "server, turn on “Allow self-signed certificate” in Settings.")
        }
    }

    // MARK: Error page (mirrors MainActivity.showError)

    private func showError(_ detail: String? = nil) {
        let host = ShouStore.activeHost().isEmpty ? "(not set)" : ShouStore.activeHost()
        let body = detail ?? ("No response from <b>\(host)</b>. Make sure the Shou server is "
            + "running on your computer and that this phone is on the same network.")
        let html = """
        <!doctype html><html><head><meta name="viewport"
          content="width=device-width,initial-scale=1,viewport-fit=cover">
        <style>
          html,body{height:100%;margin:0}
          body{background:#0B0A0E;color:#F4F1EA;font-family:-apple-system,sans-serif;
               display:flex;flex-direction:column;align-items:center;
               justify-content:center;gap:18px;text-align:center;padding:24px}
          h1{font-size:20px;margin:0;color:#FF4A32;letter-spacing:.04em}
          p{margin:0;color:#9A94A6;font-size:14px;line-height:1.5;max-width:340px}
          a{display:inline-block;margin-top:6px;padding:13px 22px;border-radius:14px;
            text-decoration:none;font-weight:700;font-size:14px}
          .p{background:#FF4A32;color:#fff}
          .s{color:#F4F1EA;border:1px solid #2a2733}
        </style></head><body>
          <h1>Can't reach Shou</h1>
          <p>\(body)</p>
          <a class="p" href="app://retry">Retry</a>
          <a class="s" href="app://settings">Settings</a>
        </body></html>
        """
        loadedURL = nil
        webView.loadHTMLString(html, baseURL: nil)
    }
}
