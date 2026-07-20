import Cocoa
import WebKit
import LocalAuthentication
import Security

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler {
    var window: NSWindow!
    private var webView: WKWebView!
    private var schemeHandler: KotobaWebBundleSchemeHandler?
    private let authMessageName = "{{AUTH_MESSAGE_NAME}}"
    private let keychainService = "{{KEYCHAIN_SERVICE}}"
    private let keychainAccount = "{{KEYCHAIN_ACCOUNT}}"

    func applicationDidFinishLaunching(_ notification: Notification) {
        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: authMessageName)
        if let bundleDir = Bundle.main.url(forResource: "WebBundle", withExtension: nil) {
            let handler = KotobaWebBundleSchemeHandler(bundleDir: bundleDir)
            schemeHandler = handler
            config.setURLSchemeHandler(handler, forURLScheme: KotobaWebBundleSchemeHandler.scheme)
        }
        let phoneSize = NSSize(width: {{WINDOW_WIDTH}}, height: {{WINDOW_HEIGHT}})
        webView = WKWebView(frame: NSRect(origin: .zero, size: phoneSize), configuration: config)
        if let indexURL = URL(string: "\(KotobaWebBundleSchemeHandler.scheme)://app/index.html") {
            webView.load(URLRequest(url: indexURL))
        }
        window = NSWindow(contentRect: NSRect(origin: .zero, size: phoneSize),
                          styleMask: [.titled, .closable, .miniaturizable],
                          backing: .buffered, defer: false)
        window.center()
        window.title = "{{APP_NAME}}"
        window.level = .floating
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.isReleasedWhenClosed = false
        window.contentView = webView
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard message.name == authMessageName,
              let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }
        switch action {
        case "request-session": unlockSession()
        case "save-session":
            guard let email = body["email"] as? String,
                  let cacao = body["cacaoB64"] as? String else { return }
            do {
                try saveSession(email: email, cacaoB64: cacao)
                dispatchSession(email: email, cacaoB64: cacao)
            } catch {
                dispatchSession(email: nil, cacaoB64: nil,
                                error: "Keychainへ保存できませんでした。")
            }
        case "delete-session":
            deleteSession()
            dispatchSession(email: nil, cacaoB64: nil)
        default: break
        }
    }

    private func unlockSession() {
        guard let stored = readSession() else {
            dispatchSession(email: nil, cacaoB64: nil)
            return
        }
        let context = LAContext()
        context.localizedCancelTitle = "キャンセル"
        var authError: NSError?
        let policy: LAPolicy = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                                                         error: &authError)
            ? .deviceOwnerAuthenticationWithBiometrics
            : .deviceOwnerAuthentication
        context.evaluatePolicy(policy,
                               localizedReason: "{{APP_NAME}}の業務セッションを解除します") { [weak self] ok, _ in
            DispatchQueue.main.async {
                if ok {
                    self?.dispatchSession(email: stored.email, cacaoB64: stored.cacaoB64)
                } else {
                    self?.dispatchSession(email: nil, cacaoB64: nil,
                                          error: "認証がキャンセルされました。")
                }
            }
        }
    }

    private func saveSession(email: String, cacaoB64: String) throws {
        let data = try JSONSerialization.data(withJSONObject: ["email": email,
                                                                "cacaoB64": cacaoB64])
        deleteSession()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    private func readSession() -> (email: String, cacaoB64: String)? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let email = value["email"], let cacao = value["cacaoB64"] else { return nil }
        return (email, cacao)
    }

    private func deleteSession() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func dispatchSession(email: String?, cacaoB64: String?, error: String? = nil) {
        let detail: [String: Any?] = ["email": email, "cacaoB64": cacaoB64, "error": error]
        let jsonObject = detail.compactMapValues { $0 }
        guard let data = try? JSONSerialization.data(withJSONObject: jsonObject),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript(
            "window.dispatchEvent(new CustomEvent('itonami-auth-session',{detail:\(json)}));")
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
