//  Theme.swift
//  The Midnight-Cinema palette, ported verbatim from the Android app's
//  res/values/colors.xml so the native chrome matches the web remote and the
//  widget pixel-for-pixel. Shared by the app and the widget extension.

import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

extension Color {
    /// `Color(hex: 0xFF4A32)` / `Color(hex: 0xFF4A32, alpha: 0.13)`.
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }
}

#if canImport(UIKit)
extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1.0) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: alpha
        )
    }
}
#endif

/// Brand tokens — names mirror `colors.xml`.
enum Brand {
    static let accentHex: UInt32     = 0xFF4A32   // primary interactive accent
    static let accentLightHex: UInt32 = 0xFF6A4D  // gradient top / wake glyph
    static let inkHex: UInt32        = 0x0B0A0E   // app background
    static let ink2Hex: UInt32       = 0x15131B   // secondary surface
    static let cardHex: UInt32       = 0x100D16   // darkest card layer
    static let fieldHex: UInt32      = 0x1B1822   // text-field / small-button fill
    static let strokeHex: UInt32     = 0x2A2733   // 1pt borders & dividers
    static let paperHex: UInt32      = 0xF4F1EA   // primary text
    static let paperDimHex: UInt32   = 0x9A94A6   // secondary / hint text
    static let iconBgHex: UInt32     = 0x17141A   // icon container

    static let accent      = Color(hex: accentHex)
    static let accentLight = Color(hex: accentLightHex)
    static let ink         = Color(hex: inkHex)
    static let ink2        = Color(hex: ink2Hex)
    static let card        = Color(hex: cardHex)
    static let field       = Color(hex: fieldHex)
    static let stroke      = Color(hex: strokeHex)
    static let paper       = Color(hex: paperHex)
    static let paperDim    = Color(hex: paperDimHex)
    static let accentSoft  = Color(hex: accentHex, alpha: 0.13)

    #if canImport(UIKit)
    static let inkUI   = UIColor(hex: inkHex)
    static let paperUI = UIColor(hex: paperHex)
    #endif
}
