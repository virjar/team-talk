import SwiftUI
import UIKit
import UserNotifications
import TeamTalk

@main
struct TeamTalkApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            // Compose 自行消费 safeDrawing insets（含键盘）；SwiftUI 若默认按安全区收缩
            // 承载视图，状态栏与底部手势条会被避让两次，页面标题出现双倍留白。
            TeamTalkView().ignoresSafeArea()
                .onAppear {
                    if scenePhase == .active { appDelegate.resumeForeground() }
                }
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active: appDelegate.resumeForeground()
                    case .background: IosApplicationRuntime.shared.didEnterBackground()
                    default: break
                    }
                }
        }
    }
}

private struct TeamTalkView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let notifications = UNUserNotificationCenter.current()
        notifications.delegate = self
        notifications.requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            if granted {
                DispatchQueue.main.async { application.registerForRemoteNotifications() }
            }
        }
        registerChatCameraBridge()
        return true
    }

    /// 应用内相机桥：Kotlin 触发呈现，三个回调恰好其一被调用（nil 取消走 onCancel）。
    private func registerChatCameraBridge() {
        IosApplicationRuntime.shared.openChatCamera = { onImage, onVideo, onCancel in
            let controller = IosChatCameraController()
            controller.onResult = { url, isImage in
                if let url {
                    if isImage { onImage(url.path) } else { onVideo(url.path) }
                } else {
                    onCancel()
                }
            }
            Self.topmostViewController()?.present(controller, animated: true)
        }
        IosApplicationRuntime.shared.dismissChatCamera = {
            if let camera = Self.topmostViewController() as? IosChatCameraController {
                camera.dismiss(animated: true)
            }
        }
    }

    private static func topmostViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }.first
        var controller = scene?.keyWindow?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        resumeForeground()
    }

    func resumeForeground() {
        IosApplicationRuntime.shared.didBecomeActive()
        // Registration is renewed after Settings changes and APNs token rotation.
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional {
                DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
            }
        }
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        IosApplicationRuntime.shared.didEnterBackground()
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken data: Data) {
        let token = data.map { String(format: "%02x", $0) }.joined()
        let signingEnvironment = Bundle.main.object(forInfoDictionaryKey: "TeamTalkPushEnvironment") as? String
        let environment: String
        switch signingEnvironment {
        case "development": environment = "sandbox"
        case "production": environment = "production"
        default:
            NSLog("TeamTalk: missing or invalid APNs signing environment")
            return
        }
        IosApplicationRuntime.shared.registerPushToken(token: token, environment: environment)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let payload = notification.request.content.userInfo
        DispatchQueue.main.async {
            guard let scope = NotificationScope(payload),
                  IosApplicationRuntime.shared.acceptsNotification(
                    deploymentFingerprint: scope.deployment, datasetId: scope.dataset, uid: scope.uid
                  ) else { completionHandler([]); return }
            completionHandler([.banner, .list, .sound, .badge])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let payload = response.notification.request.content.userInfo
        DispatchQueue.main.async {
            defer { completionHandler() }
            guard let scope = NotificationScope(payload), let chatId = payload["chatId"] as? String else { return }
            IosApplicationRuntime.shared.openNotification(
                chatId: chatId, deploymentFingerprint: scope.deployment, datasetId: scope.dataset, uid: scope.uid
            )
        }
    }
}

private struct NotificationScope {
    let deployment: String
    let dataset: String
    let uid: String

    init?(_ payload: [AnyHashable: Any]) {
        guard let deployment = payload["deploymentFingerprint"] as? String,
              let dataset = payload["datasetId"] as? String,
              let uid = payload["uid"] as? String,
              !deployment.isEmpty, !dataset.isEmpty, !uid.isEmpty else { return nil }
        self.deployment = deployment
        self.dataset = dataset
        self.uid = uid
    }
}
