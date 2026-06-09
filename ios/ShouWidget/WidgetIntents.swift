//  WidgetIntents.swift
//  AppIntents behind the interactive widget (and the iOS-18 Control). Each issues a
//  token-gated command to the active server via the shared Core, mirroring the
//  Android widget's ActionReceiver broadcasts (wake / pause / next / prev).

import AppIntents
import WidgetKit

struct WakeIntent: AppIntent {
    static var title: LocalizedStringResource = "Wake PC"
    func perform() async throws -> some IntentResult {
        let mac = ShouStore.activeMac()
        if !mac.isEmpty { await Wol.wake(mac) }
        return .result()
    }
}

struct PauseIntent: AppIntent {
    static var title: LocalizedStringResource = "Play or pause"
    func perform() async throws -> some IntentResult {
        await ServerClient.pause()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

struct NextIntent: AppIntent {
    static var title: LocalizedStringResource = "Next episode"
    func perform() async throws -> some IntentResult {
        await ServerClient.next()
        return .result()
    }
}

struct PrevIntent: AppIntent {
    static var title: LocalizedStringResource = "Previous episode"
    func perform() async throws -> some IntentResult {
        await ServerClient.prev()
        return .result()
    }
}
