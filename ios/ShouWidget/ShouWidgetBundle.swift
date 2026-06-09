//  ShouWidgetBundle.swift
//  Entry point for the widget extension: the now-playing home-screen widget, plus the
//  iOS-18 Control Center controls when the toolchain/OS supports them.

import WidgetKit
import SwiftUI

@main
struct ShouWidgetBundle: WidgetBundle {
    var body: some Widget {
        ShouWidget()
        #if compiler(>=6.0)
        if #available(iOS 18.0, *) {
            ShouPlayPauseControl()
            ShouWakeControl()
        }
        #endif
    }
}
