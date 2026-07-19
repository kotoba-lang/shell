import UIKit

struct SurfaceNode {
  var tag = "div"
  var text: String?
  var children: [Int] = []
  var attrs: [String: String] = [:]
}

func loadSurface() -> (nodes: [Int: SurfaceNode], root: Int?) {
  guard let url = Bundle.main.url(forResource: "surface", withExtension: "json"),
        let data = try? Data(contentsOf: url),
        let ops = try? JSONSerialization.jsonObject(with: data) as? [[Any]] else { return ([:], nil) }
  var nodes: [Int: SurfaceNode] = [:]
  var root: Int?
  for op in ops {
    guard let command = op.first as? String else { continue }
    switch command {
    case "create-element":
      if op.count >= 3, let id = op[1] as? Int { nodes[id] = SurfaceNode(tag: op[2] as? String ?? "div") }
    case "create-text":
      if op.count >= 3, let id = op[1] as? Int { nodes[id] = SurfaceNode(tag: "#text", text: String(describing: op[2])) }
    case "append-child":
      if op.count >= 3, let parent = op[1] as? Int, let child = op[2] as? Int { nodes[parent]?.children.append(child) }
    case "set-attr":
      if op.count >= 4, let id = op[1] as? Int { nodes[id]?.attrs[String(describing: op[2])] = String(describing: op[3]) }
    case "set-root": root = op.count >= 2 ? op[1] as? Int : nil
    default: break
    }
  }
  return (nodes, root)
}

func nativeView(id: Int, nodes: [Int: SurfaceNode]) -> UIView {
  guard let node = nodes[id] else { return UIView() }
  let directText = node.children.compactMap { nodes[$0]?.tag == "#text" ? nodes[$0]?.text : nil }.joined()
  if node.tag == "#text" || (["h1", "h2", "h3", "p", "label"].contains(node.tag) && !directText.isEmpty) {
    let label = UILabel()
    label.text = node.tag == "#text" ? node.text : directText
    label.numberOfLines = 0
    label.textColor = node.tag == "p" ? .secondaryLabel : .label
    label.font = node.tag == "h1" ? .systemFont(ofSize: 28, weight: .bold)
      : node.tag == "h2" ? .systemFont(ofSize: 19, weight: .semibold)
      : node.tag == "h3" ? .systemFont(ofSize: 15, weight: .semibold)
      : .systemFont(ofSize: 13)
    return label
  }
  if node.tag == "button" {
    var config = UIButton.Configuration.tinted()
    config.title = directText
    let button = UIButton(configuration: config)
    button.accessibilityIdentifier = node.attrs["data-action"]
    return button
  }
  let stack = UIStackView()
  stack.axis = ["header", "nav", "summary"].contains(node.tag) ? .horizontal : .vertical
  stack.alignment = .fill
  stack.spacing = node.tag == "main" ? 20 : 9
  stack.isLayoutMarginsRelativeArrangement = true
  let inset: CGFloat = ["article", "section"].contains(node.tag) ? 16 : 4
  stack.layoutMargins = UIEdgeInsets(top: inset, left: inset, bottom: inset, right: inset)
  for child in node.children where nodes[child]?.tag != "#text" { stack.addArrangedSubview(nativeView(id: child, nodes: nodes)) }
  if ["article", "section"].contains(node.tag) || (node.attrs["class"] ?? "").contains("liquid-glass__panel") {
    stack.backgroundColor = .secondarySystemBackground.withAlphaComponent(0.78)
    stack.layer.cornerRadius = 18
  }
  return stack
}

final class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?
  func application(_ application: UIApplication,
                   didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    let window = UIWindow(frame: UIScreen.main.bounds)
    let controller = UIViewController()
    controller.view.backgroundColor = .systemBackground
    let scroll = UIScrollView()
    let surface = loadSurface()
    let content = surface.root.map { nativeView(id: $0, nodes: surface.nodes) } ?? UILabel()
    scroll.translatesAutoresizingMaskIntoConstraints = false
    content.translatesAutoresizingMaskIntoConstraints = false
    controller.view.addSubview(scroll)
    scroll.addSubview(content)
    NSLayoutConstraint.activate([
      scroll.leadingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.leadingAnchor),
      scroll.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor),
      scroll.topAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.topAnchor),
      scroll.bottomAnchor.constraint(equalTo: controller.view.bottomAnchor),
      content.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 12),
      content.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -12),
      content.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 12),
      content.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -20),
      content.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -24)
    ])
    window.rootViewController = controller
    window.makeKeyAndVisible()
    self.window = window
    return true
  }
}

UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
