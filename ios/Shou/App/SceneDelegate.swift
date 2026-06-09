//  SceneDelegate.swift
//  Owns the window + the root WebShellController, and routes the things that arrive
//  at the scene: Home-Screen quick actions (per-server shortcuts + Settings) and the
//  "reload if Settings changed" on foreground (mirrors MainActivity.onResume).

import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {

    var window: UIWindow?
    private var root: WebShellController?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        let vc = WebShellController()
        root = vc
        window.rootViewController = vc
        self.window = window
        window.makeKeyAndVisible()

        if let item = connectionOptions.shortcutItem { _ = handle(item) }
        Shortcuts.publish()   // keep the dynamic shortcuts in step with saved remotes
    }

    func windowScene(_ windowScene: UIWindowScene,
                     performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        completionHandler(handle(shortcutItem))
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        root?.reloadIfChanged()
        AiringTask.schedule()
    }

    @discardableResult
    private func handle(_ item: UIApplicationShortcutItem) -> Bool {
        switch item.type {
        case Shortcuts.settingsType:
            root?.presentSettings()
            return true
        case Shortcuts.remoteType:
            if let token = item.userInfo?["token"] as? String {
                root?.switchToServer(token: token)
                return true
            }
        default:
            break
        }
        return false
    }
}
