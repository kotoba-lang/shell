import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import UniformTypeIdentifiers
import WebKit

struct SurfaceNode {
  var tag = "div"
  var text: String?
  var children: [Int] = []
  var attrs: [String: String] = [:]
}

final class KotobaActionTarget: NSObject, NSTextFieldDelegate {
  // Written when a surface is built: an action id -> its declared endpoint and
  // request body, and an input id -> that field's current text.
  var endpoints: [String: String] = [:]
  var bodies: [String: String] = [:]
  var inputValues: [String: String] = [:]

  // The only reason a field takes this object as its delegate: keep the stored
  // text current as it is typed, so an action fired later carries what the
  // person actually wrote.
  func controlTextDidChange(_ notification: Notification) {
    guard let field = notification.object as? NSTextField,
          let inputId = field.identifier?.rawValue else { return }
    inputValues[inputId] = field.stringValue
  }

  @objc func perform(_ sender: NSButton) {
    let action = sender.identifier?.rawValue ?? "unknown"
    if action == "ingest/pick-file" {
      let panel = NSOpenPanel()
      panel.allowsMultipleSelection = false
      panel.canChooseDirectories = false
      panel.allowedContentTypes = [.data]
      if panel.runModal() == .OK, let path = panel.url?.path {
        emit(["event": "input/action", "action": "ingest/file-selected", "path": path])
      } else {
        emit(["event": "input/action-cancelled", "action": action])
      }
      return
    }
    // The action consumer reads `.value` as the payload, so a button that
    // declares data-input-id has to carry that field's text. Built through
    // JSONSerialization because the text is arbitrary and would otherwise
    // break the line as soon as it contained a quote or newline.
    var event: [String: Any] = ["event": "input/action", "action": action]
    if let inputId = sender.cell?.representedObject as? String {
      event["input"] = inputId
      event["value"] = inputValues[inputId] ?? ""
    }
    if let endpoint = endpoints[action] { event["endpoint"] = endpoint }
    if let body = bodies[action] { event["body"] = body }
    emit(event)
  }
}

let actionTarget = KotobaActionTarget()

func emit(_ value: [String: Any]) {
  guard let data = try? JSONSerialization.data(withJSONObject: value),
        let line = String(data: data, encoding: .utf8) else { return }
  print(line); fflush(stdout)
}

// What this host was asked to open — from the command line, or from the bundle
// when a person opened the bundle.
//
// LaunchServices starts a double-clicked .app with no arguments, and every
// value below has a default, so the host used to come up as a 720x512 window
// titled `Kotoba` reading `No kotoba:dom root`. It looked like an application
// and it was not one. `kotoba-shell app run --execute` now writes the arguments
// it would have passed into Contents/Resources/launch-arguments.json, and this
// reads them back.
//
// `-psn_…` is what older systems append to a Finder launch; it is not one of
// ours and must not count as "arguments were given".
let hostArguments: [String] = {
  let given = CommandLine.arguments.dropFirst().filter { !$0.hasPrefix("-psn_") }
  if !given.isEmpty { return Array(given) }
  if let url = Bundle.main.url(forResource: "launch-arguments", withExtension: "json"),
     let data = try? Data(contentsOf: url),
     let recorded = (try? JSONSerialization.jsonObject(with: data)) as? [String] {
    return recorded
  }
  // Inside a bundle there is nobody to read a usage line, so refusing is the
  // only way to say this rather than opening a window that says nothing.
  // A bare binary keeps its defaults: that is a developer running it directly.
  if Bundle.main.bundleIdentifier != nil {
    emit(["event": "app/no-arguments",
          "message": "This bundle records what to open when it is built. Rebuild it with `kotoba-shell app run --target macos --manifest <app.kotoba.edn> --execute`.",
          "bundle": Bundle.main.bundlePath])
    exit(2)
  }
  return []
}()

let floatingWindow = hostArguments.contains("--floating")

func argument(_ name: String) -> String? {
  guard let index = hostArguments.firstIndex(of: name),
        hostArguments.indices.contains(index + 1) else { return nil }
  return hostArguments[index + 1]
}

func installStandardMenu(appName: String) {
  let main = NSMenu()

  let appItem = NSMenuItem()
  main.addItem(appItem)
  let appMenu = NSMenu(title: appName)
  appMenu.addItem(withTitle: "About \(appName)", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
  appMenu.addItem(.separator())
  appMenu.addItem(withTitle: "Hide \(appName)", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
  let hideOthers = appMenu.addItem(withTitle: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
  hideOthers.keyEquivalentModifierMask = [.command, .option]
  appMenu.addItem(withTitle: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: "")
  appMenu.addItem(.separator())
  appMenu.addItem(withTitle: "Quit \(appName)", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
  appItem.submenu = appMenu

  let editItem = NSMenuItem()
  main.addItem(editItem)
  let edit = NSMenu(title: "Edit")
  edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
  let redo = edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
  redo.keyEquivalentModifierMask = [.command, .shift]
  edit.addItem(.separator())
  edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
  edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
  edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
  edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
  editItem.submenu = edit

  let windowItem = NSMenuItem()
  main.addItem(windowItem)
  let windowMenu = NSMenu(title: "Window")
  windowMenu.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
  windowMenu.addItem(withTitle: "Zoom", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
  windowItem.submenu = windowMenu
  app.windowsMenu = windowMenu
  app.mainMenu = main
}

enum ComputerPermission: String, CaseIterable {
  case accessibility
  case screenRecording = "screen-recording"

  var title: String {
    switch self {
    case .accessibility: return "Accessibility"
    case .screenRecording: return "Screen Recording"
    }
  }

  var detail: String {
    switch self {
    case .accessibility: return "Allows this app to access and operate app interfaces"
    case .screenRecording: return "Allows this app to see the screen while performing tasks"
    }
  }

  var symbol: String {
    switch self {
    case .accessibility: return "accessibility"
    case .screenRecording: return "rectangle.inset.filled.and.person.filled"
    }
  }

  var settingsURL: URL? {
    let pane = self == .accessibility ? "Privacy_Accessibility" : "Privacy_ScreenCapture"
    return URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane)")
  }

  var granted: Bool {
    switch self {
    case .accessibility: return AXIsProcessTrusted()
    case .screenRecording: return CGPreflightScreenCaptureAccess()
    }
  }
}

final class PermissionOnboardingController: NSObject {
  private let appName: String
  private let permissions: [ComputerPermission]
  private weak var parent: NSWindow?
  private var panel: NSPanel?
  private var statusButtons: [ComputerPermission: NSButton] = [:]
  private var completeButton: NSButton?
  private var timer: Timer?

  init(appName: String, permissions: [ComputerPermission]) {
    self.appName = appName
    self.permissions = permissions
    super.init()
    NotificationCenter.default.addObserver(self, selector: #selector(refresh),
                                           name: NSApplication.didBecomeActiveNotification,
                                           object: nil)
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
    timer?.invalidate()
  }

  func presentIfNeeded(over window: NSWindow) {
    parent = window
    guard permissions.contains(where: { !$0.granted }) else {
      emitStatus()
      return
    }
    if panel == nil { panel = makePanel() }
    guard let panel else { return }
    if panel.parent == nil { window.addChildWindow(panel, ordered: .above) }
    let origin = NSPoint(x: window.frame.midX - panel.frame.width / 2,
                         y: window.frame.midY - panel.frame.height / 2)
    panel.setFrameOrigin(origin)
    panel.makeKeyAndOrderFront(nil)
    refresh()
    timer?.invalidate()
    timer = Timer.scheduledTimer(timeInterval: 1, target: self,
                                 selector: #selector(refresh), userInfo: nil, repeats: true)
  }

  private func makePanel() -> NSPanel {
    let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 700, height: 510),
                        styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered,
                        defer: false)
    panel.titleVisibility = .hidden
    panel.titlebarAppearsTransparent = true
    panel.isMovableByWindowBackground = true
    panel.isReleasedWhenClosed = false
    panel.standardWindowButton(.zoomButton)?.isHidden = true
    panel.standardWindowButton(.miniaturizeButton)?.isHidden = true

    let effect = NSVisualEffectView()
    effect.material = .hudWindow
    effect.blendingMode = .behindWindow
    effect.state = .active
    panel.contentView = effect

    let content = NSStackView()
    content.orientation = .vertical
    content.alignment = .centerX
    content.spacing = 18
    content.translatesAutoresizingMaskIntoConstraints = false
    effect.addSubview(content)
    NSLayoutConstraint.activate([
      content.leadingAnchor.constraint(equalTo: effect.leadingAnchor, constant: 46),
      content.trailingAnchor.constraint(equalTo: effect.trailingAnchor, constant: -46),
      content.topAnchor.constraint(equalTo: effect.topAnchor, constant: 46),
      content.bottomAnchor.constraint(lessThanOrEqualTo: effect.bottomAnchor, constant: -36)
    ])

    let icon = NSImageView(image: NSApp.applicationIconImage ?? NSImage())
    icon.imageScaling = .scaleProportionallyUpOrDown
    icon.translatesAutoresizingMaskIntoConstraints = false
    icon.widthAnchor.constraint(equalToConstant: 70).isActive = true
    icon.heightAnchor.constraint(equalToConstant: 70).isActive = true
    content.addArrangedSubview(icon)

    let heading = NSTextField(labelWithString: "Enable \(appName) Computer Use")
    heading.font = NSFont.systemFont(ofSize: 30, weight: .bold)
    heading.alignment = .center
    content.addArrangedSubview(heading)

    let explanation = NSTextField(wrappingLabelWithString:
      "\(appName) needs these permissions to use apps on your Mac.\nThese permissions are used only when you ask it to perform tasks.")
    explanation.font = NSFont.systemFont(ofSize: 15)
    explanation.textColor = .secondaryLabelColor
    explanation.alignment = .center
    content.addArrangedSubview(explanation)

    let rows = NSStackView()
    rows.orientation = .vertical
    rows.spacing = 10
    rows.translatesAutoresizingMaskIntoConstraints = false
    rows.widthAnchor.constraint(equalTo: content.widthAnchor).isActive = true
    content.addArrangedSubview(rows)
    for (index, permission) in permissions.enumerated() {
      rows.addArrangedSubview(makeRow(permission, tag: index))
    }

    let complete = NSButton(title: "COMPLETE IN SYSTEM SETTINGS", target: self,
                            action: #selector(openFirstMissing))
    complete.bezelStyle = .rounded
    complete.controlSize = .large
    complete.font = NSFont.systemFont(ofSize: 13, weight: .semibold)
    complete.translatesAutoresizingMaskIntoConstraints = false
    complete.widthAnchor.constraint(equalTo: content.widthAnchor).isActive = true
    complete.heightAnchor.constraint(equalToConstant: 58).isActive = true
    completeButton = complete
    content.addArrangedSubview(complete)
    return panel
  }

  private func makeRow(_ permission: ComputerPermission, tag: Int) -> NSView {
    let row = NSStackView()
    row.orientation = .horizontal
    row.alignment = .centerY
    row.spacing = 14
    row.edgeInsets = NSEdgeInsets(top: 13, left: 16, bottom: 13, right: 16)
    row.wantsLayer = true
    row.layer?.cornerRadius = 14
    row.layer?.backgroundColor = NSColor.controlBackgroundColor.withAlphaComponent(0.72).cgColor

    let image = NSImageView(image: NSImage(systemSymbolName: permission.symbol,
                                           accessibilityDescription: permission.title) ?? NSImage())
    image.contentTintColor = .controlAccentColor
    image.translatesAutoresizingMaskIntoConstraints = false
    image.widthAnchor.constraint(equalToConstant: 42).isActive = true
    image.heightAnchor.constraint(equalToConstant: 42).isActive = true
    row.addArrangedSubview(image)

    let labels = NSStackView()
    labels.orientation = .vertical
    labels.spacing = 3
    let title = NSTextField(labelWithString: permission.title)
    title.font = NSFont.systemFont(ofSize: 16, weight: .semibold)
    let detail = NSTextField(labelWithString: permission.detail)
    detail.font = NSFont.systemFont(ofSize: 13)
    detail.textColor = .secondaryLabelColor
    labels.addArrangedSubview(title)
    labels.addArrangedSubview(detail)
    row.addArrangedSubview(labels)

    let status = NSButton(title: "Open Settings", target: self,
                          action: #selector(openPermission(_:)))
    status.tag = tag
    status.bezelStyle = .inline
    status.font = NSFont.systemFont(ofSize: 14, weight: .semibold)
    statusButtons[permission] = status
    row.addArrangedSubview(status)
    row.widthAnchor.constraint(greaterThanOrEqualToConstant: 600).isActive = true
    return row
  }

  @objc private func openPermission(_ sender: NSButton) {
    guard permissions.indices.contains(sender.tag) else { return }
    requestAndOpen(permissions[sender.tag])
  }

  @objc private func openFirstMissing() {
    guard let permission = permissions.first(where: { !$0.granted }) else {
      dismiss()
      return
    }
    requestAndOpen(permission)
  }

  private func requestAndOpen(_ permission: ComputerPermission) {
    switch permission {
    case .accessibility:
      let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
      _ = AXIsProcessTrustedWithOptions(options)
    case .screenRecording:
      _ = CGRequestScreenCaptureAccess()
    }
    if !permission.granted, let url = permission.settingsURL {
      NSWorkspace.shared.open(url)
    }
    emit(["event": "permissions/requested", "permission": permission.rawValue])
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.refresh() }
  }

  @objc private func refresh() {
    for permission in permissions {
      let granted = permission.granted
      statusButtons[permission]?.title = granted ? "Done ✓" : "Open Settings"
      statusButtons[permission]?.isEnabled = !granted
    }
    emitStatus()
    if permissions.allSatisfy({ $0.granted }) { dismiss() }
  }

  private func emitStatus() {
    emit(["event": "permissions/status",
          "accessibility": !permissions.contains(.accessibility) || ComputerPermission.accessibility.granted,
          "screen-recording": !permissions.contains(.screenRecording) || ComputerPermission.screenRecording.granted])
  }

  private func dismiss() {
    timer?.invalidate()
    timer = nil
    guard let panel else { return }
    parent?.removeChildWindow(panel)
    panel.orderOut(nil)
  }
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
    button.cell?.representedObject = node.attrs["data-input-id"]
    if let endpoint = node.attrs["data-endpoint"] {
      actionTarget.endpoints[button.identifier?.rawValue ?? "unknown"] = endpoint
      actionTarget.bodies[button.identifier?.rawValue ?? "unknown"] = node.attrs["data-body"] ?? "{}"
    }
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
  if node.tag == "img" {
    let imageView = NSImageView(frame: .zero)
    imageView.imageScaling = .scaleProportionallyUpOrDown
    imageView.imageAlignment = .alignCenter
    if let source = node.attrs["src"] {
      imageView.image = NSImage(contentsOfFile: source)
    }
    imageView.setAccessibilityLabel(node.attrs["alt"] ?? "image")
    imageView.widthAnchor.constraint(greaterThanOrEqualToConstant: 420).isActive = true
    imageView.heightAnchor.constraint(equalToConstant: 560).isActive = true
    return imageView
  }
  if node.tag == "input" || node.tag == "textarea" {
    let field = NSTextField(string: node.attrs["value"] ?? "")
    let inputId = node.attrs["id"] ?? "input-\(id)"
    field.identifier = NSUserInterfaceItemIdentifier(inputId)
    field.placeholderString = node.attrs["placeholder"]
    field.delegate = actionTarget
    field.font = NSFont.systemFont(ofSize: 14)
    field.bezelStyle = .roundedBezel
    field.focusRingType = .default
    if node.tag == "textarea" {
      field.maximumNumberOfLines = 4
      field.usesSingleLineMode = false
      field.cell?.wraps = true
      field.cell?.isScrollable = false
      field.heightAnchor.constraint(greaterThanOrEqualToConstant: 72).isActive = true
    }
    if node.attrs["readonly"] == "true" {
      field.isEditable = false
    }
    if node.attrs["disabled"] == "true" {
      field.isEnabled = false
    }
    actionTarget.inputValues[inputId] = field.stringValue
    field.widthAnchor.constraint(greaterThanOrEqualToConstant: 260).isActive = true
    return field
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

func writePNG(image: NSImage, path: String) -> Bool {
  guard let tiff = image.tiffRepresentation,
        let rep = NSBitmapImageRep(data: tiff),
        let data = rep.representation(using: .png, properties: [:]) else { return false }
  do {
    try FileManager.default.createDirectory(at: URL(fileURLWithPath: path).deletingLastPathComponent(),
                                            withIntermediateDirectories: true)
    try data.write(to: URL(fileURLWithPath: path))
    return true
  } catch { return false }
}

// Injected before any page script, so it observes failures the page would
// otherwise swallow. A single unresolved lookup inside DOMContentLoaded takes a
// whole surface down, and that has to be reportable with no browser attached.
let webErrorHook = """
window.__kotobaErrors = [];
window.addEventListener('error', (event) => {
  window.__kotobaErrors.push(String(event.message)
    + ' @ ' + String(event.filename) + ':' + String(event.lineno));
});
window.addEventListener('unhandledrejection', (event) => {
  window.__kotobaErrors.push('unhandledrejection: ' + String(event.reason));
});
(() => {
  const original = console.error;
  console.error = (...args) => {
    window.__kotobaErrors.push('console.error: ' + args.map(String).join(' '));
    original.apply(console, args);
  };
})();
"""

// A web surface renders in a separate process, so `cacheDisplay` cannot see it
// — capture through WKWebView.takeSnapshot, and only once the page has actually
// finished loading rather than after a fixed delay.
final class KotobaWebDelegate: NSObject, WKNavigationDelegate {
  let smoke: Bool
  let screenshotPath: String?
  let settleSeconds: Double
  private var finished = false

  init(smoke: Bool, screenshotPath: String?, settleSeconds: Double) {
    self.smoke = smoke
    self.screenshotPath = screenshotPath
    self.settleSeconds = settleSeconds
  }

  private func capture(_ webView: WKWebView) {
    webView.evaluateJavaScript("JSON.stringify(window.__kotobaErrors || [])") { value, _ in
      emit(["event": "web/errors", "errors": (value as? String) ?? "[]"])
      guard let path = self.screenshotPath else {
        emit(["event": "lifecycle/smoke-ready"])
        NSApp.terminate(nil)
        return
      }
      webView.takeSnapshot(with: nil) { image, error in
        let ok = image.map { writePNG(image: $0, path: path) } ?? false
        emit(["event": "visual/captured", "ok": ok, "path": path,
              "error": error?.localizedDescription ?? ""])
        emit(["event": "lifecycle/smoke-ready"])
        NSApp.terminate(nil)
      }
    }
  }

  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    emit(["event": "web/loaded", "url": webView.url?.absoluteString ?? "",
          "title": webView.title ?? ""])
    guard smoke, !finished else { return }
    finished = true
    // Let first paint and the page's own DOMContentLoaded work settle.
    DispatchQueue.main.asyncAfter(deadline: .now() + settleSeconds) { self.capture(webView) }
  }

  private func fail(_ error: Error) {
    emit(["event": "web/failed", "message": error.localizedDescription])
    guard smoke, !finished else { return }
    finished = true
    NSApp.terminate(nil)
  }

  func webView(_ webView: WKWebView, didFail navigation: WKNavigation!,
               withError error: Error) { fail(error) }
  func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
               withError error: Error) { fail(error) }
}

// Minimal T1 native boundary. Kotoba owns application/source semantics;
// this process owns only AppKit windowing, input, resize, and lifecycle.
final class KotobaWindowDelegate: NSObject, NSWindowDelegate {
  let smoke: Bool
  weak var window: NSWindow?
  var statusItem: NSStatusItem?
  private var terminationEmitted = false
  init(smoke: Bool) { self.smoke = smoke }

  private func emitTermination(source: String? = nil) {
    guard !terminationEmitted else { return }
    terminationEmitted = true
    var event: [String: Any] = ["event": "lifecycle/terminate"]
    if let source { event["source"] = source }
    emit(event)
  }

  func configure(window: NSWindow, title: String) {
    self.window = window
    guard !smoke else { return }
    let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    if let button = item.button {
      button.image = NSImage(systemSymbolName: "bubble.left.and.bubble.right.fill",
                             accessibilityDescription: title)
      button.toolTip = "\(title) — click to open"
    }
    let menu = NSMenu()
    let open = NSMenuItem(title: "Open \(title)", action: #selector(showWindow), keyEquivalent: "")
    open.target = self
    menu.addItem(open)
    menu.addItem(.separator())
    let quit = NSMenuItem(title: "Quit \(title)", action: #selector(quitApp), keyEquivalent: "q")
    quit.target = self
    menu.addItem(quit)
    item.menu = menu
    statusItem = item
  }

  @objc func showWindow() {
    NSApp.setActivationPolicy(.regular)
    window?.deminiaturize(nil)
    window?.makeKeyAndOrderFront(nil)
    NSApp.activate(ignoringOtherApps: true)
    emit(["event": "lifecycle/restore", "source": "status-bar"])
  }

  @objc func quitApp() {
    emitTermination(source: "status-bar")
    NSApp.terminate(nil)
  }

  private func hideToStatusBar() {
    window?.orderOut(nil)
    NSApp.setActivationPolicy(.accessory)
    emit(["event": "lifecycle/status-bar"])
  }

  func windowDidBecomeKey(_ notification: Notification) {
    print("{\"event\":\"lifecycle/activate\"}"); fflush(stdout)
  }
  func windowDidResize(_ notification: Notification) {
    guard let window = notification.object as? NSWindow else { return }
    print("{\"event\":\"input/resize\",\"width\":\(Int(window.frame.width)),\"height\":\(Int(window.frame.height))}"); fflush(stdout)
  }
  func windowWillClose(_ notification: Notification) {
    print("{\"event\":\"lifecycle/terminate\"}"); fflush(stdout)
    if smoke { NSApp.terminate(nil) }
  }
}

let smoke = hostArguments.contains("--smoke")
let title = argument("--title") ?? "Kotoba"
let iconPath = argument("--icon")
let screenshotPath = argument("--screenshot")
let windowWidth = Double(argument("--width") ?? "720") ?? 720
let windowHeight = Double(argument("--height") ?? "480") ?? 480
let minWidth = Double(argument("--min-width") ?? "390") ?? 390
let minHeight = Double(argument("--min-height") ?? "320") ?? 320
let surface = surfaceNodes(from: argument("--ops-json") ?? "[]")
let webURL = argument("--web-url").flatMap(URL.init(string:))
let settleSeconds = Double(argument("--settle-seconds") ?? "1.5") ?? 1.5
let requestedPermissions = (argument("--permissions") ?? "")
  .split(separator: ",")
  .compactMap { ComputerPermission(rawValue: String($0)) }
// The menu bar and the Dock read CFBundleName; only the title bar reads
// NSWindow.title. This host is a bare executable with no Info.plist, so macOS
// fell back to the file name: an app declaring "Cloud Itonami" opened a window
// called Cloud Itonami underneath a menu bar that said
// kotoba-shell-host-macos-window.
//
// The info dictionary the KVC accessor returns is the bundle's own mutable
// one, so writing the name into it is enough — no .app wrapper, which is what
// Tauri builds for the same effect. It must happen before NSApplication.shared
// exists, because the app object reads the name once while initialising.
if let info = Bundle.main.value(forKey: "infoDictionary") as? NSMutableDictionary {
  info["CFBundleName"] = title
} else {
  emit(["event": "app/name-not-applied", "title": title])
}
let app = NSApplication.shared
installStandardMenu(appName: title)
// The Dock and the ⌘-Tab switcher read applicationIconImage. Without this an
// app declaring :app/icon still shows the generic host icon, which is the
// failure the manifest key exists to prevent — so say so on stdout rather than
// carrying on silently with the wrong icon.
if let iconPath {
  if let icon = NSImage(contentsOfFile: iconPath) {
    app.applicationIconImage = icon
    emit(["event": "app/icon-applied", "path": iconPath])
  } else {
    emit(["event": "app/icon-unreadable", "path": iconPath])
  }
}
app.setActivationPolicy(.regular)
// NSWindow.delegate is weak, so this top-level binding is what keeps the
// delegate alive for the life of the process.
let delegate = KotobaWindowDelegate(smoke: smoke)
let permissionOnboarding = PermissionOnboardingController(appName: title,
                                                          permissions: requestedPermissions)
let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: windowWidth, height: windowHeight), styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
window.title = title
if floatingWindow {
  window.level = .floating
  window.collectionBehavior.insert(.canJoinAllSpaces)
  window.collectionBehavior.insert(.fullScreenAuxiliary)
}
window.minSize = NSSize(width: minWidth, height: minHeight)
window.level = .floating
window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
window.delegate = delegate
window.isReleasedWhenClosed = false
delegate.configure(window: window, title: title)
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
// `--web-url` hosts an app's web surface. Until this existed the flag was
// parsed and discarded, so every app exporting KOTOBA_SHELL_WEB_URL silently
// got the kotoba:dom surface instead.
var retainedWebDelegate: KotobaWebDelegate?
var retainedWebView: WKWebView?
if let webURL {
  let configuration = WKWebViewConfiguration()
  let controller = WKUserContentController()
  controller.addUserScript(WKUserScript(source: webErrorHook,
                                        injectionTime: .atDocumentStart,
                                        forMainFrameOnly: true))
  configuration.userContentController = controller
  let webView = WKWebView(frame: window.contentView?.bounds ?? .zero,
                          configuration: configuration)
  webView.autoresizingMask = [.width, .height]
  let webDelegate = KotobaWebDelegate(smoke: smoke, screenshotPath: screenshotPath,
                                      settleSeconds: settleSeconds)
  webView.navigationDelegate = webDelegate
  retainedWebDelegate = webDelegate
  retainedWebView = webView
  window.contentView?.addSubview(webView)
  if webURL.isFileURL {
    webView.loadFileURL(webURL, allowingReadAccessTo: webURL.deletingLastPathComponent())
  } else {
    webView.load(URLRequest(url: webURL))
  }
} else {
  commitSurface(surface, reason: "launch")
  window.contentView?.addSubview(scroll)
}
window.center()
if smoke {
  // An observation boundary must not become the active app: this machine runs
  // many concurrent sessions, and stealing activation corrupts whichever one is
  // being typed into.
  app.setActivationPolicy(.accessory)
  window.level = .normal
  window.orderFrontRegardless()
} else {
  window.makeKeyAndOrderFront(nil)
  app.activate(ignoringOtherApps: true)
  DispatchQueue.main.async {
    permissionOnboarding.presentIfNeeded(over: window)
  }
}
if webURL == nil, let document = scroll.documentView {
  scroll.contentView.scroll(to: NSPoint(x: 0,
                                        y: max(0, document.frame.height - scroll.contentView.bounds.height - 16)))
  scroll.reflectScrolledClipView(scroll.contentView)
}
if let webURL {
  emit(["event": "lifecycle/launch", "surface": "web", "runtime": "native-webkit",
        "url": webURL.absoluteString])
} else {
  emit(["event": "lifecycle/launch", "surface": "kotoba:dom",
        "runtime": "native-appkit", "ops": surface.nodes.count])
}

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
// A web surface captures from its navigation delegate once loading finishes;
// only the kotoba:dom surface is ready after a fixed delay.
if smoke, webURL == nil {
  DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
    // Visual capture is an observation boundary: do not inherit an IME
    // composition or focus animation from whichever app was active before us.
    window.makeFirstResponder(nil)
    window.displayIfNeeded()
    if let path = screenshotPath {
      let captured = writePNG(view: window.contentView!, path: path)
      print("{\"event\":\"visual/captured\",\"ok\":\(captured),\"path\":\"\(path)\"}"); fflush(stdout)
    }
    print("{\"event\":\"lifecycle/smoke-ready\"}"); fflush(stdout)
    window.close()
  }
}
app.run()
