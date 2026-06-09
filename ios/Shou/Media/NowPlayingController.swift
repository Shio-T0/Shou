//  NowPlayingController.swift
//  The iOS counterpart of PlaybackService.kt + its MediaStyle notification: Lock-Screen
//  and Control-Center transport controls that drive the PC's mpv. iOS only surfaces a
//  Now Playing card while an audio session is active, so we hold the slot with a silent
//  looping player while something is playing on the PC, and clear it when nothing is.
//
//  Caveat (documented in the README): to stay polite we mix with other audio, so iOS
//  may not always promote us to the foreground Now Playing app — the controls are
//  best-effort, exactly as the plan calls out.

import Foundation
import MediaPlayer
import AVFoundation

final class NowPlayingController {

    static let shared = NowPlayingController()
    private init() {}

    private var commandsWired = false
    private var silentPlayer: AVAudioPlayer?
    private var current = Playback.idle

    /// Push the latest playback snapshot from the web remote to the system.
    func update(_ pb: Playback) {
        DispatchQueue.main.async { self.apply(pb) }
    }

    private func apply(_ pb: Playback) {
        current = pb
        guard pb.active, pb.duration > 0 else { tearDown(); return }

        wireCommandsOnce()
        startSilentSession()

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: pb.title.isEmpty ? "Shou" : pb.title,
            MPMediaItemPropertyArtist: pb.subtitle.isEmpty ? ShouStore.activeName() : pb.subtitle,
            MPMediaItemPropertyPlaybackDuration: pb.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: pb.position,
            MPNowPlayingInfoPropertyPlaybackRate: pb.playing ? 1.0 : 0.0,
        ]
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        // Cover art arrives asynchronously; re-stamp the info dict once it's cached.
        let coverURL = pb.cover
        Task { [weak self] in
            guard !coverURL.isEmpty, let image = await ArtLoader.image(for: coverURL) else { return }
            await MainActor.run {
                guard let self, self.current.cover == coverURL else { return }
                let art = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                info[MPMediaItemPropertyArtwork] = art
                MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            }
        }
    }

    private func tearDown() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        silentPlayer?.stop()
        silentPlayer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    // MARK: Silent session (keeps the controls alive while the PC plays)

    private func startSilentSession() {
        if let p = silentPlayer, p.isPlaying { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, options: [.mixWithOthers])
        try? session.setActive(true)
        if silentPlayer == nil {
            silentPlayer = try? AVAudioPlayer(data: Self.silentWav())
            silentPlayer?.numberOfLoops = -1
            silentPlayer?.volume = 0
            silentPlayer?.prepareToPlay()
        }
        silentPlayer?.play()
    }

    // MARK: Remote command center -> control endpoints

    private func wireCommandsOnce() {
        guard !commandsWired else { return }
        commandsWired = true
        let c = MPRemoteCommandCenter.shared()

        c.togglePlayPauseCommand.isEnabled = true
        c.togglePlayPauseCommand.addTarget { _ in Self.fire { await ServerClient.pause() } }
        c.playCommand.isEnabled = true
        c.playCommand.addTarget { _ in Self.fire { await ServerClient.pause() } }
        c.pauseCommand.isEnabled = true
        c.pauseCommand.addTarget { _ in Self.fire { await ServerClient.pause() } }

        c.nextTrackCommand.isEnabled = true
        c.nextTrackCommand.addTarget { _ in Self.fire { await ServerClient.next() } }
        c.previousTrackCommand.isEnabled = true
        c.previousTrackCommand.addTarget { _ in Self.fire { await ServerClient.prev() } }

        c.skipForwardCommand.isEnabled = true
        c.skipForwardCommand.preferredIntervals = [30]
        c.skipForwardCommand.addTarget { _ in Self.fire { await ServerClient.fwd() } }
        c.skipBackwardCommand.isEnabled = true
        c.skipBackwardCommand.preferredIntervals = [30]
        c.skipBackwardCommand.addTarget { _ in Self.fire { await ServerClient.rew() } }
    }

    private static func fire(_ work: @escaping () async -> Void) -> MPRemoteCommandHandlerStatus {
        Task { await work() }
        return .success
    }

    // MARK: A few ms of silence as WAV bytes (no bundled asset needed)

    private static func silentWav() -> Data {
        let sampleRate = 8000, seconds = 1, channels = 1, bits = 16
        let frames = sampleRate * seconds
        let dataBytes = frames * channels * (bits / 8)
        var d = Data()
        func str(_ s: String) { d.append(contentsOf: s.utf8) }
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        str("RIFF"); u32(UInt32(36 + dataBytes)); str("WAVE")
        str("fmt "); u32(16); u16(1); u16(UInt16(channels))
        u32(UInt32(sampleRate))
        u32(UInt32(sampleRate * channels * (bits / 8)))   // byte rate
        u16(UInt16(channels * (bits / 8)))                // block align
        u16(UInt16(bits))
        str("data"); u32(UInt32(dataBytes))
        d.append(Data(count: dataBytes))                  // zeros = silence
        return d
    }
}
