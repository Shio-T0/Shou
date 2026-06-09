//  ShouWidget.swift
//  The home-screen now-playing strip — the iOS counterpart of widget_shou.xml +
//  ShouWidgetProvider.kt. A `.systemMedium` widget with cover art, title/subtitle,
//  a gradient progress bar, and inline wake / prev / play-pause / next buttons wired
//  to AppIntents (iOS 17 interactive widgets). Colours/radii are the Brand tokens.

import WidgetKit
import SwiftUI
import AppIntents

// MARK: - Timeline

struct ShouEntry: TimelineEntry {
    let date: Date
    let active: Bool
    let title: String
    let subtitle: String
    let position: Double
    let duration: Double
    let playing: Bool
    let cover: Image?
}

struct ShouProvider: TimelineProvider {

    func placeholder(in context: Context) -> ShouEntry {
        ShouEntry(date: Date(), active: true, title: "Now playing",
                  subtitle: "Episode 1", position: 540, duration: 1440,
                  playing: true, cover: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (ShouEntry) -> Void) {
        completion(makeEntry(cover: nil))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ShouEntry>) -> Void) {
        let coverURL = ShouStore.playback()?.cover ?? ""
        Task {
            var image: Image?
            if !coverURL.isEmpty, let ui = await ArtLoader.image(for: coverURL) {
                image = Image(uiImage: ui)
            }
            let entry = makeEntry(cover: image)
            // Refresh periodically so the bar creeps forward when the app isn't open;
            // the app also force-reloads on every playback tick while it's foreground.
            let next = Calendar.current.date(byAdding: .second, value: 30, to: Date()) ?? Date()
            completion(Timeline(entries: [entry], policy: .after(next)))
        }
    }

    /// Build an entry from the shared snapshot, extrapolating the position forward by
    /// however long ago the snapshot was written (only while playing).
    private func makeEntry(cover: Image?) -> ShouEntry {
        guard let pb = ShouStore.playback(), pb.active, pb.duration > 0 else {
            return ShouEntry(date: Date(), active: false, title: "", subtitle: "",
                             position: 0, duration: 0, playing: false, cover: nil)
        }
        let drift = pb.playing ? ShouStore.playbackAge() : 0
        let pos = min(pb.duration, pb.position + max(0, drift))
        return ShouEntry(date: Date(), active: true,
                         title: pb.title.isEmpty ? "Shou" : pb.title,
                         subtitle: pb.subtitle.isEmpty ? ShouStore.activeName() : pb.subtitle,
                         position: pos, duration: pb.duration, playing: pb.playing, cover: cover)
    }
}

// MARK: - View

struct ShouWidgetView: View {
    let entry: ShouEntry

    var body: some View {
        Group {
            if entry.active { playingBody } else { idleBody }
        }
        .containerBackground(for: .widget) {
            LinearGradient(colors: [Color(hex: 0x1A1722), Color(hex: 0x131019), Brand.ink],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    private var playingBody: some View {
        VStack(spacing: 9) {
            HStack(spacing: 10) {
                cover
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.title).font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Brand.paper).lineLimit(1)
                    Text(entry.subtitle).font(.system(size: 11))
                        .foregroundStyle(Brand.paperDim).lineLimit(1)
                }
                Spacer(minLength: 6)
                circleButton(intent: WakeIntent(), systemName: "power",
                             tint: Brand.accentLight, size: 34, fill: Brand.field)
            }
            progressRow
            HStack(spacing: 14) {
                circleButton(intent: PrevIntent(), systemName: "backward.end.fill",
                             tint: Brand.paper, size: 36, fill: Brand.field)
                circleButton(intent: PauseIntent(),
                             systemName: entry.playing ? "pause.fill" : "play.fill",
                             tint: .white, size: 46, fill: Brand.accent)
                circleButton(intent: NextIntent(), systemName: "forward.end.fill",
                             tint: Brand.paper, size: 36, fill: Brand.field)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
    }

    private var idleBody: some View {
        VStack(spacing: 10) {
            Image(systemName: "play.circle.fill")
                .font(.system(size: 30)).foregroundStyle(Brand.accent)
            Text("Nothing playing").font(.system(size: 13, weight: .medium))
                .foregroundStyle(Brand.paperDim)
            circleButton(intent: WakeIntent(), systemName: "power",
                         tint: Brand.accentLight, size: 38, fill: Brand.field)
        }
    }

    // Cover art (or the gradient placeholder + glyph, matching widget_cover_placeholder).
    private var cover: some View {
        ZStack {
            if let img = entry.cover {
                img.resizable().scaledToFill()
            } else {
                LinearGradient(colors: [Color(hex: 0x221B16), Brand.ink2],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "play.fill").font(.system(size: 14)).foregroundStyle(Brand.accent)
            }
        }
        .frame(width: 42, height: 42)
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous)
            .stroke(Brand.stroke, lineWidth: 1))
    }

    private var progressRow: some View {
        HStack(spacing: 9) {
            Text(fmt(entry.position)).font(.system(size: 10, design: .monospaced))
                .foregroundStyle(Brand.paperDim)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.1))
                    Capsule()
                        .fill(LinearGradient(colors: [Brand.accent, Brand.accentLight],
                                             startPoint: .leading, endPoint: .trailing))
                        .frame(width: max(0, min(1, fraction)) * geo.size.width)
                }
            }
            .frame(height: 5)
            Text(fmt(entry.duration)).font(.system(size: 10, design: .monospaced))
                .foregroundStyle(Brand.paperDim)
        }
    }

    private var fraction: Double {
        entry.duration > 0 ? entry.position / entry.duration : 0
    }

    private func circleButton<I: AppIntent>(intent: I, systemName: String, tint: Color,
                                            size: CGFloat, fill: Color) -> some View {
        Button(intent: intent) {
            ZStack {
                Circle().fill(fill)
                Image(systemName: systemName)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: size, height: size)
        }
        .buttonStyle(.plain)
    }

    private func fmt(_ s: Double) -> String {
        let total = max(0, Int(s))
        let h = total / 3600, m = (total % 3600) / 60, sec = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec)
                     : String(format: "%d:%02d", m, sec)
    }
}

// MARK: - Widget

struct ShouWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ShouNowPlaying", provider: ShouProvider()) { entry in
            ShouWidgetView(entry: entry)
        }
        .configurationDisplayName("Shou — Now Playing")
        .description("Wake the PC and control what's playing, without opening the app.")
        .supportedFamilies([.systemMedium])
    }
}
