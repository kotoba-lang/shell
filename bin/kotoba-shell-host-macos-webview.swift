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
  var visualTimer: Timer?
  var visualSnapshotInFlight = false
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
    if ProcessInfo.processInfo.environment["TAMAKI_VISUAL_SNAPSHOT_PATH"] != nil {
      visualTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) {
        [weak self] _ in self?.writeVisualSnapshot()
      }
      DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
        [weak self] in self?.writeVisualSnapshot()
      }
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
  func writeVisualSnapshot() {
    guard !visualSnapshotInFlight,
          let webView = webView,
          let path = ProcessInfo.processInfo.environment[
            "TAMAKI_VISUAL_SNAPSHOT_PATH"] else { return }
    visualSnapshotInFlight = true
    let configuration = WKSnapshotConfiguration()
    configuration.afterScreenUpdates = true
    webView.takeSnapshot(with: configuration) { [weak self] image, error in
      defer { self?.visualSnapshotInFlight = false }
      guard error == nil,
            let image = image,
            let tiff = image.tiffRepresentation,
            let bitmap = NSBitmapImageRep(data: tiff),
            let png = bitmap.representation(using: .png, properties: [:])
      else {
        if let error = error { print("visual snapshot: \(error)") }
        return
      }
      let destination = URL(fileURLWithPath: path)
      let temporary = destination.appendingPathExtension("next")
      do {
        try FileManager.default.createDirectory(
          at: destination.deletingLastPathComponent(),
          withIntermediateDirectories: true)
        try png.write(to: temporary, options: .atomic)
        _ = try FileManager.default.replaceItemAt(
          destination, withItemAt: temporary)
      } catch {
        do {
          try? FileManager.default.removeItem(at: destination)
          try FileManager.default.moveItem(at: temporary, to: destination)
        } catch {
          print("visual snapshot: \(error)")
        }
      }
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
