//  AppDelegate.swift
//  UIKit lifecycle entry point. The app is fundamentally a WebView shell, so a
//  UIKit lifecycle (with a SceneDelegate) gives the cleanest control over the
//  WKWebView, orientation lock, Home-Screen quick actions, and background tasks.

import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    /// Drives application(_:supportedInterfaceOrientationsFor:). Default lets the
    /// remote rotate; the cast player locks it to landscape via the bridge.
    static var orientationLock: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        Notifications.setup()
        AiringTask.register()
        AiringTask.schedule()
        return true
    }

    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientationLock
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }
}
