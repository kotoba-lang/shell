# kotoba-lang/shell

Zero-dep portable `.cljc` — restored from the legacy `kotoba-lang/kotoba`
`crates/kotoba-shell` Rust crate (deleted in PR #259, 2026-07-01, along with
the rest of the legacy Rust workspace) as part of the **kotoba-shell CLJC
restoration** (ADR-2607012115, `com-junkawasaki/root`).

`kotoba-shell` was the proposed Tauri replacement for manimani: an app shell
(WebView / window / menu / app lifecycle, native capability providers, macOS
`.app` / iOS Xcode / Android Gradle packaging) governed by kotoba's capability
confinement model instead of Tauri's. See the original design docs in
`kotoba-lang/kotoba`: `docs/ADR-kotoba-shell-aiueos-safe-kotoba.md` and
`docs/ADR-kotoba-shell-aiueos-safety-clj.md`.

## Status

Scaffold only — the CLJC restoration is pending. This repo provides the home
for the zero-dep portable `.cljc` contracts / manifest parsing / `ShellPlan`
capability model that replace the deleted Rust crate. Native execution
(WKWebView dev runner, Xcode/Gradle project generation, codesign/notarization)
stays substrate — a future host adapter, not the semantic authority.

When actual restoration starts, follow the higher-fidelity precedent set by
`kotoba-lang/rt` (a real ported contract with tests and a documented scope
boundary) rather than stopping at scaffold: start with manifest parsing /
`ShellPlan` / target-capability resolution, per ADR-2607010000's four-layer
discipline (EDN/CLJC is the authority; native code only executes
already-validated requests).

## Known gap to fix in any future native adapter

The last real `kotoba shell dev` run (pre-deletion binary, 2026-07-01
17:19–17:22 JST) crashed twice: the generated macOS dev runner invokes
`UNUserNotificationCenter.current()` from an unbundled `swift <script>`
process, which has no valid `mainBundle` and throws
`NSInternalInconsistencyException: bundleProxyForCurrentProcess is nil`. Any
future native adapter must guard that call (e.g. skip/stub when
`Bundle.main.bundleIdentifier == nil`) rather than calling it unconditionally.

## Develop

```bash
clojure -M:test
```
