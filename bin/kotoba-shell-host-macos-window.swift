import AppKit
import Foundation

// Minimal T1 native boundary. Kotoba owns application/source semantics;
// this process owns only AppKit windowing, input, resize, and lifecycle.
final class KotobaWindowDelegate: NSObject, NSWindowDelegate {
  let smoke: Bool
  init(smoke: Bool) { self.smoke = smoke }
  func windowDidBecomeKey(_ notification: Notification) {
    print("{\"event\":\"lifecycle/activate\"}"); fflush(stdout)
  }
  func windowDidResize(_ notification: Notification) {
    guard let window = notification.object as? NSWindow else { return }
    print("{\"event\":\"input/resize\",\"width\":\(Int(window.frame.width)),\"height\":\(Int(window.frame.height))}"); fflush(stdout)
  }
  func windowWillClose(_ notification: Notification) {
    print("{\"event\":\"lifecycle/terminate\"}"); fflush(stdout)
    if smoke { NSApp.stop(nil) }
  }
}

let smoke = CommandLine.arguments.contains("--smoke")
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let delegate = KotobaWindowDelegate(smoke: smoke)
let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 480), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
window.title = "Kotoba"
window.delegate = delegate
window.isReleasedWhenClosed = false
let label = NSTextField(labelWithString: "Kotoba native surface")
label.alignment = .center
label.frame = NSRect(x: 20, y: 210, width: 680, height: 40)
window.contentView?.addSubview(label)
window.center()
window.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)
print("{\"event\":\"lifecycle/launch\",\"surface\":\"kotoba:dom\",\"runtime\":\"native-appkit\"}"); fflush(stdout)
if smoke {
  DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
    print("{\"event\":\"lifecycle/smoke-ready\"}"); fflush(stdout)
    window.close()
  }
}
app.run()
