//  ShouControl.swift
//  The iOS-18 Control Center "Control" — the closest analogue to the Android Quick
//  Settings tile (ShouTileService.kt): a one-swipe Wake and Play/Pause from Control
//  Center / the Lock Screen. Gated to iOS 18 + Xcode 16 (the Controls API), so the
//  iOS-17 floor and older toolchains still build the rest of the app.

#if compiler(>=6.0)   // ControlWidget ships in the iOS 18 SDK (Xcode 16+)
import WidgetKit
import SwiftUI
import AppIntents

@available(iOS 18.0, *)
struct ShouPlayPauseControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "io.github.shiot0.shou.control.playpause") {
            ControlWidgetButton(action: PauseIntent()) {
                Label("Shou", systemImage: "playpause.fill")
            }
        }
        .displayName("Shou — Play/Pause")
        .description("Toggle play/pause on the Shou computer.")
    }
}

@available(iOS 18.0, *)
struct ShouWakeControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "io.github.shiot0.shou.control.wake") {
            ControlWidgetButton(action: WakeIntent()) {
                Label("Wake PC", systemImage: "power")
            }
        }
        .displayName("Shou — Wake PC")
        .description("Send a Wake-on-LAN packet to your Shou computer.")
    }
}
#endif
