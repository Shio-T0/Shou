//  BonjourScanner.swift
//  mDNS discovery of `_shou._tcp` servers on the LAN, mirroring NsdScanner.kt.
//  Resolves each service to host + port and hands back de-duplicated results — used
//  by both the in-WebView "Scan" button (via the bridge) and the native Settings
//  screen. Requires Info.plist NSBonjourServices + NSLocalNetworkUsageDescription.

import Foundation

struct BonjourFound: Equatable {
    let name: String
    let host: String
    let port: Int
}

final class BonjourScanner: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {

    private let browser = NetServiceBrowser()
    private var pending = Set<NetService>()           // services mid-resolve (retain them)
    private var seen = Set<String>()                  // dedupe by name|host|port
    private var onFound: ((BonjourFound) -> Void)?
    private var onDone: (() -> Void)?
    private var timer: Timer?

    /// Start a scan. `onFound` fires per resolved server; `onDone` fires once at
    /// timeout (or on failure). Call on the main thread.
    func start(timeout: TimeInterval = 9,
               onFound: @escaping (BonjourFound) -> Void,
               onDone: @escaping () -> Void) {
        self.onFound = onFound
        self.onDone = onDone
        browser.delegate = self
        browser.schedule(in: .main, forMode: .common)
        browser.searchForServices(ofType: "_shou._tcp.", inDomain: "local.")
        timer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
            self?.finish()
        }
    }

    func stop() { finish() }

    private func finish() {
        timer?.invalidate(); timer = nil
        browser.stop()
        for s in pending { s.stop() }
        pending.removeAll()
        let done = onDone
        onDone = nil; onFound = nil
        done?()
    }

    // MARK: NetServiceBrowserDelegate

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService,
                           moreComing: Bool) {
        service.delegate = self
        pending.insert(service)
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser,
                           didNotSearch errorDict: [String: NSNumber]) {
        finish()
    }

    // MARK: NetServiceDelegate

    func netServiceDidResolveAddress(_ sender: NetService) {
        defer { pending.remove(sender) }
        let host = resolvedHost(sender)
        guard !host.isEmpty, sender.port > 0 else { return }
        let name = label(from: sender.name)
        let dedupe = "\(name)|\(host)|\(sender.port)"
        guard !seen.contains(dedupe) else { return }
        seen.insert(dedupe)
        onFound?(BonjourFound(name: name, host: host, port: sender.port))
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        pending.remove(sender)
    }

    // MARK: Helpers

    /// Prefer the portable `<name>.local` hostname; fall back to a resolved IPv4.
    private func resolvedHost(_ svc: NetService) -> String {
        if let h = svc.hostName, !h.isEmpty {
            return h.hasSuffix(".") ? String(h.dropLast()) : h
        }
        for case let data as Data in svc.addresses ?? [] {
            if let ip = Self.ipv4(from: data) { return ip }
        }
        return ""
    }

    /// "Shou (living-room)" -> "living-room"; otherwise the bare name.
    private func label(from serviceName: String) -> String {
        if let open = serviceName.firstIndex(of: "("),
           let close = serviceName.firstIndex(of: ")"),
           open < close {
            let inner = serviceName[serviceName.index(after: open)..<close]
            let trimmed = inner.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty { return trimmed }
        }
        return serviceName
    }

    private static func ipv4(from data: Data) -> String? {
        data.withUnsafeBytes { raw -> String? in
            guard let sa = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self),
                  sa.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
            var addr = raw.baseAddress!.assumingMemoryBound(to: sockaddr_in.self).pointee.sin_addr
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &addr, &buf, socklen_t(INET_ADDRSTRLEN))
            return String(cString: buf)
        }
    }
}
