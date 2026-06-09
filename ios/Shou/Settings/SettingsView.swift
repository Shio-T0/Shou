//  SettingsView.swift
//  The native connection screen — the iOS counterpart of activity_settings.xml /
//  SettingsActivity.kt: brand header, a card with Computer (+ Bonjour Scan), Port,
//  Remote token, and the HTTPS / self-signed / volume toggles, then "Save & connect".
//  Colours, radii and type follow the Brand tokens so it matches the Android form.

import SwiftUI
import UIKit

struct SettingsView: View {
    let onClose: () -> Void

    @State private var host = ShouStore.host()
    @State private var port = ShouStore.port()
    @State private var token = ShouStore.token()
    @State private var useHTTPS = ShouStore.https()
    @State private var allowBadCerts = ShouStore.allowBadCerts()

    @State private var scanner: BonjourScanner?
    @State private var scanStatus = ""
    @State private var scanning = false

    var body: some View {
        ZStack {
            Brand.ink.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 22) {
                    header
                    card
                    saveButton
                }
                .padding(.horizontal, 24)
                .padding(.top, 28)
                .padding(.bottom, 32)
            }
        }
        .overlay(alignment: .topTrailing) {
            Button("Cancel", action: onClose)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Brand.paperDim)
                .padding(.trailing, 20).padding(.top, 14)
        }
    }

    // MARK: Sections

    private var header: some View {
        VStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Brand.ink2)
                .frame(width: 74, height: 74)
                .overlay(Image(systemName: "play.fill").font(.system(size: 30))
                    .foregroundStyle(Brand.accent))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Brand.stroke, lineWidth: 1))
            Text("Shou Remote").font(.system(size: 26, weight: .bold))
                .foregroundStyle(Brand.paperDim)
            Text("PHONE REMOTE").font(.system(size: 12, weight: .bold))
                .tracking(2.6).foregroundStyle(Brand.accent)
            Text("Point this remote at the computer running Shou.")
                .font(.system(size: 13)).foregroundStyle(Brand.paperDim)
                .multilineTextAlignment(.center).lineSpacing(3)
                .padding(.top, 2)
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 14) {
            label("COMPUTER")
            field("192.168.1.20", text: $host, keyboard: .URL)
            Button(action: scan) {
                HStack(spacing: 8) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                    Text(scanning ? "Scanning…" : "Scan network for Shou")
                }
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Brand.accent)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .stroke(Brand.accent, lineWidth: 1))
            }
            .disabled(scanning)
            if !scanStatus.isEmpty {
                Text(scanStatus).font(.system(size: 12)).foregroundStyle(Brand.paperDim)
            }

            label("PORT")
            field("4100", text: $port, keyboard: .numberPad)

            label("REMOTE TOKEN")
            field("paste token", text: $token, secure: true)
            Text("The REMOTE_TOKEN from ~/.config/shou/shou.conf")
                .font(.system(size: 11, design: .monospaced)).foregroundStyle(Brand.paperDim)

            Divider().overlay(Brand.stroke).padding(.vertical, 4)

            toggle("Use HTTPS", isOn: $useHTTPS)
            toggle("Allow self-signed certificate", isOn: $allowBadCerts)
            // Present for parity with Android, but iOS can't intercept volume keys.
            HStack {
                Text("Volume buttons control playback")
                    .font(.system(size: 14)).foregroundStyle(Brand.paperDim)
                Spacer()
                Toggle("", isOn: .constant(false)).labelsHidden().disabled(true)
            }
            Text("Not available on iOS — use the on-screen volume controls.")
                .font(.system(size: 11)).foregroundStyle(Brand.paperDim)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Brand.card))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Brand.stroke, lineWidth: 1))
    }

    private var saveButton: some View {
        Button(action: save) {
            Text("Save & connect")
                .font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Brand.accent))
        }
    }

    // MARK: Reusable bits

    private func label(_ text: String) -> some View {
        Text(text).font(.system(size: 11, weight: .bold)).tracking(1.1)
            .foregroundStyle(Brand.paperDim)
    }

    private func field(_ placeholder: String, text: Binding<String>,
                       keyboard: UIKeyboardType = .default, secure: Bool = false) -> some View {
        Group {
            if secure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
                    .keyboardType(keyboard)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
        }
        .font(.system(size: 16)).foregroundStyle(Brand.paper)
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(Brand.field))
        .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).stroke(Brand.stroke, lineWidth: 1))
    }

    private func toggle(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(title).font(.system(size: 14)).foregroundStyle(Brand.paper)
        }
        .tint(Brand.accent)
    }

    // MARK: Actions

    private func scan() {
        scanning = true
        scanStatus = "Scanning…"
        var filled = false
        let s = BonjourScanner()
        scanner = s
        s.start(onFound: { found in
            guard !filled else { return }
            filled = true
            host = found.host
            port = String(found.port)
            scanStatus = "Found Shou at \(found.host):\(found.port)"
            s.stop()
        }, onDone: {
            scanning = false
            if !filled { scanStatus = "No Shou server found on this network." }
            scanner = nil
        })
    }

    private func save() {
        let h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = port.trimmingCharacters(in: .whitespacesAndNewlines)
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        ShouStore.saveSettings(host: h, port: p, token: t,
                               https: useHTTPS, allowBadCerts: allowBadCerts)
        // Make the typed-in server the active one (mirrors SettingsActivity).
        ShouStore.setActive(token: t, host: h, port: p.isEmpty ? "4100" : p, name: h)
        onClose()
    }
}

/// UIHostingController wrapper so the WebView shell can present the SwiftUI screen and
/// reload when it's dismissed.
final class SettingsHostingController: UIHostingController<SettingsView> {
    init(onClose: @escaping () -> Void) {
        super.init(rootView: SettingsView(onClose: onClose))
        view.backgroundColor = Brand.inkUI
    }
    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
