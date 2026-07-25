import AppKit
import Foundation
import WebKit

final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {
  let root: URL
  init(root: URL) { self.root = root }
  func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
    guard let url = task.request.url else {
      task.didFailWithError(URLError(.badURL)); return
    }
    let relative = url.path == "/" || url.path.isEmpty ? "index.html" :
      String(url.path.dropFirst())
    let file = root.appendingPathComponent(relative)
    guard let data = try? Data(contentsOf: file) else {
      task.didFailWithError(URLError(.fileDoesNotExist)); return
    }
    let types = ["html": "text/html", "js": "application/javascript",
                 "css": "text/css", "json": "application/json",
                 "wasm": "application/wasm", "map": "application/json"]
    let response = URLResponse(url: url,
      mimeType: types[file.pathExtension.lowercased()] ?? "application/octet-stream",
      expectedContentLength: data.count, textEncodingName: "utf-8")
    task.didReceive(response); task.didReceive(data); task.didFinish()
  }
  func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}

final class ConsoleBridge: NSObject, WKScriptMessageHandler {
  func userContentController(_ controller: WKUserContentController,
                             didReceive message: WKScriptMessage) {
    print("web: \(message.body)"); fflush(stdout)
  }
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
  let root: URL
  var window: NSWindow?
  var schemeHandler: BundleSchemeHandler?
  var consoleBridge: ConsoleBridge?
  var webView: WKWebView?
  var snapshotTimer: Timer?
  init(root: URL) { self.root = root }
  func applicationDidFinishLaunching(_ notification: Notification) {
    let config = WKWebViewConfiguration()
    let handler = BundleSchemeHandler(root: root)
    schemeHandler = handler
    config.setURLSchemeHandler(handler, forURLScheme: "tamaki")
    let bridge = ConsoleBridge()
    consoleBridge = bridge
    config.userContentController.add(bridge, name: "console")
    let diagnostics = WKUserScript(source:
      "window.addEventListener('error',e=>webkit.messageHandlers.console.postMessage(e.message));",
      injectionTime: .atDocumentStart, forMainFrameOnly: true)
    config.userContentController.addUserScript(diagnostics)
    let webView = WKWebView(frame: .zero, configuration: config)
    self.webView = webView
    webView.setValue(false, forKey: "drawsBackground")
    let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 760),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
    window.title = "Tamaki Observatory · Three.js"
    window.minSize = NSSize(width: 720, height: 520)
    window.level = .floating
    window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
    window.contentView = webView
    window.delegate = self
    window.center()
    window.makeKeyAndOrderFront(nil)
    self.window = window
    webView.load(URLRequest(url: URL(string: "tamaki://app/index.html")!))
    snapshotTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) {
      [weak self] _ in self?.pushSnapshot()
    }
    NSApp.activate(ignoringOtherApps: true)
  }
  func pushSnapshot() {
    let file = root.appendingPathComponent("snapshot.json")
    guard let data = try? Data(contentsOf: file),
          let json = String(data: data, encoding: .utf8) else { return }
    webView?.evaluateJavaScript(
      "window.tamakiReceive && window.tamakiReceive(\(json))") { _, error in
        if let error = error { print("snapshot bridge: \(error)") }
      }
  }
  func windowWillClose(_ notification: Notification) { NSApp.terminate(nil) }
}

guard CommandLine.arguments.count >= 2 else {
  fputs("usage: host DIST_DIR\n", stderr); exit(2)
}
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let delegate = AppDelegate(root: URL(fileURLWithPath: CommandLine.arguments[1],
                                     isDirectory: true))
app.delegate = delegate
app.run()
