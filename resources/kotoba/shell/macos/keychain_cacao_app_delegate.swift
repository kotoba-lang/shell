import Cocoa
import WebKit
import LocalAuthentication
import Security
import AuthenticationServices

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler,
                         ASWebAuthenticationPresentationContextProviding {
    var window: NSWindow!
    private var webView: WKWebView!
    private var schemeHandler: KotobaWebBundleSchemeHandler?
    private let authMessageName = "{{AUTH_MESSAGE_NAME}}"
    private let keychainService = "{{KEYCHAIN_SERVICE}}"
    private let keychainAccount = "{{KEYCHAIN_ACCOUNT}}"
    // OAuth/OIDC redirect の custom scheme。app.kotoba.edn の
    // :macos/oauth-callback-scheme。空文字なら open-authorization-url は
    // 「未設定」を返す — 適当な scheme で待ち受けて他アプリの redirect を
    // 拾うより、設定されていないことを明示する方が安全。
    private let oauthCallbackScheme = "{{OAUTH_CALLBACK_SCHEME}}"
    // 進行中の session を保持する。ローカル変数だと ARC に回収されて
    // ダイアログが即座に消える（ASWebAuthenticationSession の古典的な罠）。
    private var authSession: ASWebAuthenticationSession?

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
        case "open-authorization-url":
            guard let url = body["url"] as? String else {
                dispatchAuthorization(callbackURL: nil, error: "url がありません。")
                return
            }
            openAuthorizationURL(url)
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

    // MARK: - open-authorization-url
    //
    // これが host 言語に残る唯一の capability。WKWebView を IdP へ遷移させれば
    // 済むように見えるが駄目な理由が2つある:
    //   1. Google は embedded webview からの OAuth を拒否する
    //      (disallowed_useragent)。
    //   2. 自分が制御する WebView に IdP のログイン画面を出すと、アプリが
    //      資格情報を覗ける構造になる。ASWebAuthenticationSession は
    //      別プロセスのブラウザで動くのでそれが不可能になる — この API が
    //      存在する理由そのもの。
    //
    // 判断はしない。URL を開いて callback URL をそのまま JS へ返すだけで、
    // state/nonce/PKCE の照合も claims 検証も portable 側が行う。
    private func openAuthorizationURL(_ urlString: String) {
        guard !oauthCallbackScheme.isEmpty else {
            dispatchAuthorization(callbackURL: nil,
                                  error: "oauth-callback-scheme が未設定です。")
            return
        }
        guard let url = URL(string: urlString), url.scheme == "https" else {
            // https 以外を開かない。ここを緩めると、注入された任意 URL を
            // OS のブラウザセッションで開ける踏み台になる。
            dispatchAuthorization(callbackURL: nil, error: "認可 URL が不正です。")
            return
        }
        let session = ASWebAuthenticationSession(
            url: url,
            callbackURLScheme: oauthCallbackScheme) { [weak self] callbackURL, error in
                guard let self = self else { return }
                self.authSession = nil
                if let error = error {
                    let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                    self.dispatchAuthorization(
                        callbackURL: nil,
                        error: cancelled ? nil : error.localizedDescription,
                        cancelled: cancelled)
                    return
                }
                self.dispatchAuthorization(callbackURL: callbackURL?.absoluteString, error: nil)
            }
        session.presentationContextProvider = self
        // 既存のブラウザセッションを再利用しない。共有 cookie で「前回の
        // アカウントに黙って入る」のを防ぐ — 組織アカウント限定のアプリで
        // これは実害になる。
        session.prefersEphemeralWebBrowserSession = true
        authSession = session
        if !session.start() {
            authSession = nil
            dispatchAuthorization(callbackURL: nil, error: "認証セッションを開始できませんでした。")
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        window
    }

    private func dispatchAuthorization(callbackURL: String?, error: String?,
                                       cancelled: Bool = false) {
        let detail: [String: Any?] = ["callbackURL": callbackURL, "error": error,
                                      "cancelled": cancelled]
        let jsonObject = detail.compactMapValues { $0 }
        guard let data = try? JSONSerialization.data(withJSONObject: jsonObject),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript(
            "window.dispatchEvent(new CustomEvent('kotoba-shell-authorization',{detail:\(json)}));")
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
