//  Wol.swift
//  Wake-on-LAN. Turning a sleeping Shou PC on from the couch is the whole point of
//  the phone being a "remote", so each saved server can store the PC's MAC and we
//  broadcast a standard magic packet (6×0xFF + 16× MAC) over UDP to wake it.
//
//  iOS gates UDP broadcast behind the `com.apple.developer.networking.multicast`
//  entitlement (Apple-approved per app id) — see Shou.entitlements. Ported from Wol.kt.

import Foundation
import Network

enum Wol {

    private static let ports: [UInt16] = [9, 7]   // common WOL ports; send to both

    /// Build + broadcast the magic packet. Accepts MACs as AA:BB:.. / AA-BB-.. / aabb..
    /// Returns true if at least one datagram went out.
    @discardableResult
    static func wake(_ mac: String) async -> Bool {
        guard let bytes = parseMac(mac) else { return false }
        var packet = Data(repeating: 0xFF, count: 6)
        for _ in 0..<16 { packet.append(contentsOf: bytes) }

        var sent = false
        for port in ports {
            if await sendBroadcast(packet, port: port) { sent = true }
        }
        return sent
    }

    private static func sendBroadcast(_ packet: Data, port: UInt16) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            let endpoint = NWEndpoint.hostPort(
                host: NWEndpoint.Host("255.255.255.255"),
                port: NWEndpoint.Port(rawValue: port)!
            )
            let conn = NWConnection(to: endpoint, using: .udp)
            var resumed = false
            func finish(_ ok: Bool) {
                guard !resumed else { return }
                resumed = true
                conn.cancel()
                cont.resume(returning: ok)
            }
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    conn.send(content: packet, completion: .contentProcessed { error in
                        finish(error == nil)
                    })
                case .failed, .cancelled:
                    finish(false)
                default:
                    break
                }
            }
            conn.start(queue: .global(qos: .utility))
            // Safety net so a stuck connection can't hang the caller.
            DispatchQueue.global().asyncAfter(deadline: .now() + 2) { finish(false) }
        }
    }

    /// 12 hex digits (with optional :/-/. separators) -> 6 bytes, or nil if malformed.
    private static func parseMac(_ mac: String) -> [UInt8]? {
        let hex = mac.filter(\.isHexDigit)
        guard hex.count == 12 else { return nil }
        var out = [UInt8]()
        var idx = hex.startIndex
        for _ in 0..<6 {
            let next = hex.index(idx, offsetBy: 2)
            guard let b = UInt8(hex[idx..<next], radix: 16) else { return nil }
            out.append(b)
            idx = next
        }
        return out
    }
}
