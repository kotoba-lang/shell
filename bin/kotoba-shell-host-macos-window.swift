import AppKit
import Foundation
import UniformTypeIdentifiers

struct SurfaceNode {
  var tag = "div"
  var text: String?
  var children: [Int] = []
  var attrs: [String: String] = [:]
}

final class KotobaActionTarget: NSObject {
  @objc func perform(_ sender: NSButton) {
    let action = sender.identifier?.rawValue ?? "unknown"
    if action == "ingest/pick-file" {
      let panel = NSOpenPanel()
      panel.allowsMultipleSelection = false
      panel.canChooseDirectories = false
      panel.allowedContentTypes = [.data]
      if panel.runModal() == .OK, let path = panel.url?.path {
        print("{\"event\":\"input/action\",\"action\":\"ingest/file-selected\",\"path\":\"\(path)\"}"); fflush(stdout)
      } else {
        print("{\"event\":\"input/action-cancelled\",\"action\":\"\(action)\"}"); fflush(stdout)
      }
    } else {
      print("{\"event\":\"input/action\",\"action\":\"\(action)\"}"); fflush(stdout)
    }
  }
}

let actionTarget = KotobaActionTarget()

func emit(_ value: [String: Any]) {
  guard let data = try? JSONSerialization.data(withJSONObject: value),
        let line = String(data: data, encoding: .utf8) else { return }
  print(line); fflush(stdout)
}

func argument(_ name: String) -> String? {
  guard let index = CommandLine.arguments.firstIndex(of: name),
        CommandLine.arguments.indices.contains(index + 1) else { return nil }
  return CommandLine.arguments[index + 1]
}

func surfaceNodes(from json: String) -> (nodes: [Int: SurfaceNode], root: Int?) {
  guard let data = json.data(using: .utf8),
        let ops = try? JSONSerialization.jsonObject(with: data) as? [[Any]] else { return ([:], nil) }
  var nodes: [Int: SurfaceNode] = [:]
  var root: Int?
  for op in ops {
    guard let command = op.first as? String else { continue }
    switch command {
    case "create-element":
      guard op.count >= 3, let id = op[1] as? Int else { continue }
      nodes[id] = SurfaceNode(tag: op[2] as? String ?? "div")
    case "create-text":
      guard op.count >= 3, let id = op[1] as? Int else { continue }
      nodes[id] = SurfaceNode(tag: "#text", text: String(describing: op[2]))
    case "append-child":
      guard op.count >= 3, let parent = op[1] as? Int, let child = op[2] as? Int else { continue }
      nodes[parent]?.children.append(child)
    case "set-attr":
      guard op.count >= 4, let id = op[1] as? Int else { continue }
      nodes[id]?.attrs[String(describing: op[2])] = String(describing: op[3])
    case "set-root":
      root = op.count >= 2 ? op[1] as? Int : nil
    default: continue
    }
  }
  return (nodes, root)
}

func nativeView(id: Int, nodes: [Int: SurfaceNode]) -> NSView {
  guard let node = nodes[id] else { return NSView() }
  let className = node.attrs["class"] ?? ""
  if className.contains("liquid-glass__specular") {
    let marker = NSView(frame: .zero)
    marker.isHidden = true
    return marker
  }
  if node.tag == "#text" {
    let label = NSTextField(wrappingLabelWithString: node.text ?? "")
    label.isSelectable = true
    return label
  }
  let directText = node.children.compactMap { nodes[$0]?.tag == "#text" ? nodes[$0]?.text : nil }.joined()
  if node.tag == "button" {
    let button = NSButton(title: directText, target: actionTarget, action: #selector(KotobaActionTarget.perform(_:)))
    button.identifier = NSUserInterfaceItemIdentifier(node.attrs["data-action"] ?? "unknown")
    button.bezelStyle = .rounded
    button.controlSize = .large
    button.font = NSFont.systemFont(ofSize: 13, weight: .medium)
    if className.contains("liquid-glass__button") {
      button.wantsLayer = true
      button.layer?.cornerRadius = 10
      button.layer?.backgroundColor = NSColor(red: 20/255, green: 20/255, blue: 24/255, alpha: 0.42).cgColor
      button.layer?.borderColor = NSColor.white.withAlphaComponent(0.12).cgColor
      button.layer?.borderWidth = 0.5
      button.contentTintColor = .labelColor
    }
    return button
  }
  if ["h1", "h2", "h3", "p", "label"].contains(node.tag), !directText.isEmpty {
    let label = NSTextField(wrappingLabelWithString: directText)
    label.isSelectable = true
    switch node.tag {
    case "h1": label.font = NSFont.systemFont(ofSize: 28, weight: .bold)
    case "h2": label.font = NSFont.systemFont(ofSize: 19, weight: .semibold)
    case "h3": label.font = NSFont.systemFont(ofSize: 15, weight: .semibold)
    case "p":
      label.font = NSFont.systemFont(ofSize: 13)
      label.textColor = .secondaryLabelColor
    default: label.font = NSFont.systemFont(ofSize: 12, weight: .medium)
    }
    return label
  }
  let stack = NSStackView()
  let horizontal = ["header", "nav", "summary"].contains(node.tag)
  stack.orientation = horizontal ? .horizontal : .vertical
  stack.alignment = horizontal ? .centerY : .leading
  stack.distribution = .fill
  stack.setHuggingPriority(.required, for: .vertical)
  stack.spacing = node.tag == "main" ? 20 : (["nav", "summary"].contains(node.tag) ? 10 : 8)
  let inset: CGFloat = ["article", "section"].contains(node.tag) ? 16 : 4
  stack.edgeInsets = NSEdgeInsets(top: inset, left: inset, bottom: inset, right: inset)
  for child in node.children { stack.addArrangedSubview(nativeView(id: child, nodes: nodes)) }
  if ["article", "section"].contains(node.tag) || className.contains("liquid-glass__panel") {
    stack.wantsLayer = true
    stack.layer?.cornerRadius = className.contains("liquid-glass__panel") ? 16 : 12
    stack.layer?.backgroundColor = className.contains("liquid-glass__panel")
      ? NSColor(red: 20/255, green: 20/255, blue: 24/255, alpha: 0.14).cgColor
      : NSColor.controlBackgroundColor.cgColor
    stack.layer?.borderColor = className.contains("liquid-glass__panel")
      ? NSColor.white.withAlphaComponent(0.12).cgColor
      : NSColor.separatorColor.cgColor
    stack.layer?.borderWidth = 0.5
    if className.contains("liquid-glass__panel") {
      stack.shadow = NSShadow()
      stack.shadow?.shadowColor = NSColor.black.withAlphaComponent(0.28)
      stack.shadow?.shadowBlurRadius = 18
      stack.shadow?.shadowOffset = NSSize(width: 0, height: -6)
    }
  }
  if className.contains("liquid-glass__panel") {
    let glass = NSVisualEffectView()
    glass.material = .hudWindow
    glass.blendingMode = .behindWindow
    glass.state = .active
    glass.wantsLayer = true
    glass.layer?.cornerRadius = 16
    glass.layer?.masksToBounds = true
    stack.translatesAutoresizingMaskIntoConstraints = false
    glass.addSubview(stack)
    NSLayoutConstraint.activate([
      stack.leadingAnchor.constraint(equalTo: glass.leadingAnchor),
      stack.trailingAnchor.constraint(equalTo: glass.trailingAnchor),
      stack.topAnchor.constraint(equalTo: glass.topAnchor),
      stack.bottomAnchor.constraint(equalTo: glass.bottomAnchor)
    ])
    return glass
  }
  return stack
}

func writePNG(view: NSView, path: String) -> Bool {
  view.layoutSubtreeIfNeeded()
  guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil,
                                   pixelsWide: Int(view.bounds.width),
                                   pixelsHigh: Int(view.bounds.height),
                                   bitsPerSample: 8,
                                   samplesPerPixel: 4,
                                   hasAlpha: true,
                                   isPlanar: false,
                                   colorSpaceName: .deviceRGB,
                                   bytesPerRow: 0,
                                   bitsPerPixel: 0) else { return false }
  rep.size = view.bounds.size
  view.cacheDisplay(in: view.bounds, to: rep)
  guard let data = rep.representation(using: .png, properties: [:]) else { return false }
  do {
    try FileManager.default.createDirectory(at: URL(fileURLWithPath: path).deletingLastPathComponent(),
                                            withIntermediateDirectories: true)
    try data.write(to: URL(fileURLWithPath: path))
    return true
  } catch { return false }
}

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
let title = argument("--title") ?? "Kotoba"
let screenshotPath = argument("--screenshot")
let windowWidth = Double(argument("--width") ?? "720") ?? 720
let windowHeight = Double(argument("--height") ?? "480") ?? 480
let minWidth = Double(argument("--min-width") ?? "390") ?? 390
let minHeight = Double(argument("--min-height") ?? "320") ?? 320
let surface = surfaceNodes(from: argument("--ops-json") ?? "[]")
let app = NSApplication.shared
app.setActivationPolicy(.regular)
let delegate = KotobaWindowDelegate(smoke: smoke)
let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: windowWidth, height: windowHeight), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
window.title = title
window.minSize = NSSize(width: minWidth, height: minHeight)
window.delegate = delegate
window.isReleasedWhenClosed = false
let scroll = NSScrollView(frame: window.contentView?.bounds ?? .zero)
scroll.autoresizingMask = [.width, .height]
scroll.hasVerticalScroller = true

func commitSurface(_ surface: (nodes: [Int: SurfaceNode], root: Int?), reason: String) {
  let previousOffset = scroll.contentView.bounds.origin
  if let root = surface.root {
  let content = nativeView(id: root, nodes: surface.nodes)
  let intrinsicHeight = content.fittingSize.height + 32
  let documentHeight = max(intrinsicHeight, windowHeight - 16)
  let documentWidth = max(320, windowWidth - 32)
  content.frame = NSRect(x: 0, y: documentHeight - intrinsicHeight,
                         width: documentWidth, height: intrinsicHeight)
  content.autoresizingMask = [.width]
  let document = NSView(frame: NSRect(x: 0, y: 0, width: documentWidth, height: documentHeight))
  document.addSubview(content)
  scroll.documentView = document
  scroll.contentView.scroll(to: NSPoint(x: previousOffset.x,
                                        y: min(previousOffset.y, max(0, documentHeight - scroll.contentView.bounds.height))))
  scroll.reflectScrolledClipView(scroll.contentView)
  } else {
    scroll.documentView = NSTextField(labelWithString: "No kotoba:dom root")
  }
  emit(["event": "surface/committed", "reason": reason, "ops": surface.nodes.count])
}
commitSurface(surface, reason: "launch")
window.contentView?.addSubview(scroll)
window.center()
window.makeKeyAndOrderFront(nil)
app.activate(ignoringOtherApps: true)
if let document = scroll.documentView {
  scroll.contentView.scroll(to: NSPoint(x: 0,
                                        y: max(0, document.frame.height - scroll.contentView.bounds.height - 16)))
  scroll.reflectScrolledClipView(scroll.contentView)
}
print("{\"event\":\"lifecycle/launch\",\"surface\":\"kotoba:dom\",\"runtime\":\"native-appkit\",\"ops\":\(surface.nodes.count)}"); fflush(stdout)

// Newline-delimited JSON control plane. A live reload only replaces the native
// surface; the NSWindow and its focus, geometry and scroll identity stay alive.
var inputBuffer = Data()
FileHandle.standardInput.readabilityHandler = { handle in
  let data = handle.availableData
  guard !data.isEmpty else { return }
  inputBuffer.append(data)
  while let newline = inputBuffer.firstIndex(of: 0x0A) {
    let lineData = Data(inputBuffer[inputBuffer.startIndex..<newline])
    inputBuffer.removeSubrange(inputBuffer.startIndex...newline)
    guard let message = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any],
          message["command"] as? String == "surface/commit",
          let ops = message["ops"],
          let opsData = try? JSONSerialization.data(withJSONObject: ops),
          let opsJSON = String(data: opsData, encoding: .utf8) else { continue }
    DispatchQueue.main.async { commitSurface(surfaceNodes(from: opsJSON), reason: "hot-reload") }
  }
}
if smoke {
  DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
    if let path = screenshotPath {
      let captured = writePNG(view: window.contentView!, path: path)
      print("{\"event\":\"visual/captured\",\"ok\":\(captured),\"path\":\"\(path)\"}"); fflush(stdout)
    }
    print("{\"event\":\"lifecycle/smoke-ready\"}"); fflush(stdout)
    window.close()
  }
}
app.run()
