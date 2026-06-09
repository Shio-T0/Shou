//  AiringTask.swift
//  Background "new episode aired" check — the iOS counterpart of AiringWorker.kt. Polls
//  the active server's token-gated /airing feed and notifies once per newly-aired episode
//  of a show you're watching. Ported logic: prime the seen-set silently on first run,
//  notify when available > progress, cap the seen-set so it can't grow unbounded.
//
//  Caveat (documented in the README): iOS — not the app — decides when background refresh
//  actually runs, so this is best-effort and not the fixed 30-minute cadence Android uses.

import Foundation
import BackgroundTasks

enum AiringTask {

    static let identifier = "io.github.shiot0.shou.airing"
    private static let seenKey = "airing_seen"
    private static let primedKey = "airing_primed"
    private static let seenCap = 200

    private static var defaults: UserDefaults { UserDefaults(suiteName: ShouStore.appGroup) ?? .standard }

    /// Register the handler — must run before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            handle(task as! BGAppRefreshTask)
        }
    }

    /// Ask iOS to run us again no sooner than ~30 minutes from now.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()   // always queue the next one
        let work = Task { await poll() }
        task.expirationHandler = { work.cancel() }
        Task {
            _ = await work.value
            task.setTaskCompleted(success: true)
        }
    }

    /// Poll /airing and fire notifications for newly-available episodes.
    static func poll() async {
        guard let resp = await ServerClient.airing() else { return }
        let d = defaults
        var seen = d.stringArray(forKey: seenKey) ?? []
        var seenSet = Set(seen)
        let primed = d.bool(forKey: primedKey)

        for show in resp.shows where show.available > show.progress {
            let marker = "\(show.id):\(show.available)"
            guard !seenSet.contains(marker) else { continue }
            seenSet.insert(marker)
            seen.append(marker)
            if primed {
                let newCount = show.available - show.progress
                Notifications.postEvent(
                    kind: "airing",
                    title: show.title,
                    body: "Episode \(show.available) is out — \(newCount) new to watch")
            }
        }

        if seen.count > seenCap { seen = Array(seen.suffix(seenCap)) }
        d.set(seen, forKey: seenKey)
        d.set(true, forKey: primedKey)   // first run only primes; later runs notify
    }
}
