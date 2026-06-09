//  Notifications.swift
//  Local notifications for the two event kinds the Android app posts — "you finished
//  a series" (from the web remote's rating screen) and "a new episode aired" (from the
//  background airing check). iOS has no notification channels, so a single authorisation
//  covers both; events are de-duped by hashing kind|title, mirroring Notifications.kt.

import Foundation
import UserNotifications

enum Notifications {

    /// Ask once for permission (the iOS analogue of MainActivity's runtime request).
    static func setup() {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// Post (or coalesce) an event notification. `kind` ("finished" / "airing") + title
    /// form a stable identifier so repeats of the same event replace rather than stack.
    static func postEvent(kind: String, title: String, body: String) {
        guard !title.isEmpty else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let id = "\(kind)|\(title)"
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}
