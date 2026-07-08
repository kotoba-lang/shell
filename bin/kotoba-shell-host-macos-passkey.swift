#!/usr/bin/env swift
import AppKit
import AuthenticationServices
import CryptoKit
import Foundation

// kotoba-shell macOS native passkey + WebAuthn PRF extension provider.
// Invoked by bin/kotoba-shell-host-macos for the `webauthn/register` and
// `webauthn/assert` provider commands. Calls the real
// ASAuthorizationPlatformPublicKeyCredentialProvider API (macOS 15+) so
// passkey creation/authentication and PRF secret derivation go through the
// OS's actual platform authenticator (Touch ID / password fallback) --
// NOT a WebView `navigator.credentials` call, which Tauri's embedded
// webview does not reliably support.
//
// Output: one JSON object on stdout, {"ok":true,...} or {"ok":false,"error":...}.

func emitJSON(_ obj: [String: Any]) {
    let data = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write("\n".data(using: .utf8)!)
}

func die(_ message: String) -> Never {
    emitJSON(["ok": false, "error": message])
    exit(1)
}

func parseArg(_ name: String) -> String? {
    let args = CommandLine.arguments
    guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
    return args[i + 1]
}

func requiredBase64Arg(_ name: String) -> Data {
    guard let s = parseArg(name), let d = Data(base64Encoded: s) else {
        die("\(name) is required and must be base64")
    }
    return d
}

/// Returns nil if `name` was not passed at all; dies loudly if it WAS passed
/// but isn't valid base64 (rather than silently falling back, which would
/// hide a corrupted round-tripped value -- e.g. a truncated credential_id --
/// behind a *different*, unintended value or code path).
func optionalBase64Arg(_ name: String) -> Data? {
    guard let s = parseArg(name) else { return nil }
    guard let d = Data(base64Encoded: s) else {
        die("\(name) was given but is not valid base64")
    }
    return d
}

final class PasskeyRunner: NSObject, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding
{
    let window: NSWindow
    init(window: NSWindow) { self.window = window }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        window
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        switch authorization.credential {
        case let cred as ASAuthorizationPlatformPublicKeyCredentialRegistration:
            var out: [String: Any] = [
                "ok": true,
                "credential_id": cred.credentialID.base64EncodedString(),
            ]
            // isSupported and a present `first` are independent: an
            // authenticator can support PRF (isSupported == true) while
            // still deferring the actual salted evaluation to a follow-up
            // `assert` call (first == nil at registration time is normal,
            // per the PRF extension spec) -- that is NOT "unsupported".
            if let prf = cred.prf {
                out["prf_supported"] = prf.isSupported
                out["prf_first"] = prf.first.map { first in
                    first.withUnsafeBytes { Data($0) }.base64EncodedString() as Any
                } ?? NSNull()
            } else {
                out["prf_supported"] = false
                out["prf_first"] = NSNull()
            }
            emitJSON(out)
        case let cred as ASAuthorizationPlatformPublicKeyCredentialAssertion:
            var out: [String: Any] = [
                "ok": true,
                "credential_id": cred.credentialID.base64EncodedString(),
            ]
            if let prf = cred.prf {
                out["prf_first"] = prf.first.withUnsafeBytes { Data($0) }.base64EncodedString()
            } else {
                out["prf_first"] = NSNull()
            }
            emitJSON(out)
        default:
            emitJSON(["ok": false, "error": "unexpected credential type from authenticator"])
        }
        exit(0)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        emitJSON(["ok": false, "error": error.localizedDescription])
        exit(1)
    }
}

guard CommandLine.arguments.count > 1 else {
    die("usage: kotoba-shell-host-macos-passkey register|assert --rp-id X --challenge B64 --salt B64 [--user-name N] [--user-id B64] [--credential-id B64]")
}
let mode = CommandLine.arguments[1]
guard let rpId = parseArg("--rp-id") else { die("--rp-id is required") }
let challenge = requiredBase64Arg("--challenge")
let salt = requiredBase64Arg("--salt")
let prfInputValues = ASAuthorizationPublicKeyCredentialPRFAssertionInput.InputValues(saltInput1: salt)

let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
let request: ASAuthorizationRequest

switch mode {
case "register":
    guard let userName = parseArg("--user-name") else { die("--user-name is required for register") }
    let userId = optionalBase64Arg("--user-id") ?? Data(userName.utf8)
    let reg = provider.createCredentialRegistrationRequest(challenge: challenge, name: userName, userID: userId)
    reg.prf = .inputValues(prfInputValues)
    request = reg
case "assert":
    let assertion = provider.createCredentialAssertionRequest(challenge: challenge)
    assertion.prf = .inputValues(prfInputValues)
    if let credData = optionalBase64Arg("--credential-id") {
        assertion.allowedCredentials = [ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: credData)]
    }
    request = assertion
default:
    die("mode must be 'register' or 'assert', got: \(mode)")
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let window = NSWindow(
    contentRect: NSRect(x: 0, y: 0, width: 1, height: 1),
    styleMask: [.titled], backing: .buffered, defer: false)
// The Touch ID / password authorization sheet attaches to this window as its
// presentation anchor -- it must actually be key/frontmost for the system to
// have somewhere to attach it, and the full AppKit run loop (app.run(), not
// a bare RunLoop.main.run()) is what pumps window-server/sheet machinery.
window.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)

let runner = PasskeyRunner(window: window)
let controller = ASAuthorizationController(authorizationRequests: [request])
controller.delegate = runner
controller.presentationContextProvider = runner
controller.performRequests()

app.run()
