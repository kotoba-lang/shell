# kotoba-shell

`kotoba-lang/shell` is the authority repo for the Kotoba shell adapter: the
Tauri-like native-host bridge, provider catalog, and desktop/mobile host
contracts.

The old `kotoba-lang/kotoba` CLI no longer keeps a compatibility shim for
`kotoba shell ...`; shell work and shell gates should call this CLI directly.

## Status

Everything below this section is a command reference for the full CLI
surface; not every command is equally mature. This section says plainly what
is real and verified today versus still aspirational, so a consumer doesn't
have to rediscover the gap by hitting it (as `local-manimani`'s own
integration did more than once).

- **`app scaffold`/`app build` (macOS, iOS): real, verified.** `app scaffold`
  generates an XcodeGen `project.yml` and runs `xcodegen generate` itself
  (not a hand-written `project.pbxproj`), producing a project that
  `xcodebuild` actually builds — confirmed end to end, including installing
  and launching the built app on a real booted iOS Simulator and
  screenshotting real WKWebView-rendered content. CI (`.github/workflows/
  ci.yml`) runs a real `app scaffold` + `app build --execute` for both
  targets on every push, not just a file-presence check.
- **`app scaffold`/`app build` (Android): real, verified end to end,
  including a real booted emulator install+launch.** Generates a real
  WebView-hosting `MainActivity.java` and a Gradle project that `gradle
  assembleDebug` builds into a real `app-debug.apk`. CI now runs a real
  `app scaffold` + `app build --execute` for Android on every push (same
  pattern as the macOS/iOS smoke tests), after installing `gradle` via
  Homebrew. **Real gotcha found while wiring this up**: Gradle 9.x lets AGP's
  `androidJdkImage` transform (the `jlink` step over `core-for-system-
  modules.jar`, needed for `compileSdk` 33+) auto-detect whichever JDK it
  finds newest among all installed JDKs, not whatever `java` resolves to on
  `PATH` — with a too-new JDK (e.g. Homebrew's unversioned `openjdk`, which
  tracks latest upstream) this fails with `Could not resolve all files for
  configuration ':app:androidJdkImage'` even though AGP 8.5.0 + `gradle
  assembleDebug` otherwise runs fine. Fixed (both in CI and reproduced
  locally) by pinning `JAVA_HOME` to a JDK 17 install before invoking
  `gradle` — AGP 8.5.0 is only tested up to `compileSdk` 34/JDK 17-ish, so
  don't assume "whatever JDK happens to be newest on this machine" works.
  Beyond the build, also verified installing+launching on a real booted
  Android Emulator (`system-images;android-34;google_apis;arm64-v8a` via
  `avdmanager`/`emulator`, not just `gradle assembleDebug`): `adb install`
  + `adb shell am start` on the generated `MainActivity` renders the
  placeholder page correctly, confirmed via `adb exec-out screencap`.
  Repeated with `local-manimani/mobile`'s real production bundle via
  `:web/dist-dir` and got the same `Could not find namespace
  kotoba-ui.theme.` error already found on macOS/iOS (see below) —
  independent third-platform confirmation that this is a real
  `local-manimani` bug, not a WKWebView-specific or kotoba-shell-specific
  one. **Bonus finding**: unlike WKWebView's `loadFileURL`, Android's
  `android.webkit.WebView` logs full JS console/error output — message,
  source file, line, and even surrounding source context — to `adb logcat`
  (tag `chromium`, `[INFO:CONSOLE(...)]`) by default, with no
  `WebChromeClient.onConsoleMessage` override and no diagnostic bridge
  needed. Android's default observability here is strictly better than
  WKWebView's.
- **`app scaffold`/`app build` (Windows): scaffolding only, unverified.** The
  generated `Package.appxmanifest`/`.wapproj` skeleton has never been run
  through `msbuild` — there is no Windows CI runner and no Windows
  development machine has exercised this path.
- **Rendering substrate: WKWebView (macOS/iOS) and `android.webkit.WebView`
  (Android) today, not `kotoba-lang/dom-gpu`/`kotoba-lang/browser`.**
  `surface check`'s `:ui-substrate`/`:browser-engine` fields describe the
  long-term target architecture (see ADR-2607081015 in the superproject);
  `:render-substrate` in the same data says what actually renders content in
  a scaffolded app right now. dom-gpu/browser were R0-stage with no real-app
  adoption at the time of that decision — this is a deliberate, documented
  pragmatic choice, not an oversight.
- **Fixed: nested `:web/dist-dir` directories (e.g. `vendor/`) were silently
  flattened out of the macOS/iOS app bundle.** What was first recorded here
  as an "opaque WKWebView error with large bundles" turned out to be a real
  packaging bug, root-caused with a `WKScriptMessageHandler` bridge (inject a
  `WKUserScript` that relays `console.*`/`window.onerror`/
  `unhandledrejection` to native code, which appends to a file under the
  app's Documents directory, readable via `xcrun simctl get_app_container
  <udid> <bundle-id> data` — no Safari Web Inspector needed, fully
  CLI-scriptable). Xcode's default "Copy Bundle Resources" build phase
  flattens nested directories added as a normal group reference: a source
  tree with `Resources/vendor/scittle.js` landed as `<bundle>/scittle.js`
  (no `vendor/` at all), so `index.html`'s `<script src="vendor/scittle.js">`
  ended up pointing at nothing and every `resource failed to load` fired at
  once. `:web/dist-dir` content is now copied into `Resources/WebBundle`,
  which the XcodeGen spec (`xcodegen-project-yml`) declares as a `type:
  folder` **folder reference** instead of a plain path — folder references
  are copied recursively, preserving structure, unlike group references.
  Verified against `local-manimani/mobile`'s full real UI bundle
  (~225KB, `vendor/` included): the built app's CSS now visibly renders
  (background gradient, no longer blank white), confirming the fix.
- **Fixed: `loadFileURL` (`file://` origin) redacted every uncaught JS error
  to the opaque placeholder `"Script error."`, with zero message/filename/
  line/column/stack.** With the packaging bug above fixed, `vendor/
  scittle.js` etc. loaded correctly (no more 404s), but evaluating
  manimani's real bundle still surfaced only `"Script error."` and nothing
  else via `window.onerror`. Isolated in a minimal reproduction (not
  manimani-specific): loading *any* HTML/JS — inline `<script>`, external
  `<script src>`, even a plain `throw new Error(...)` with no `eval`/
  `Function` involved — via `WKWebView.loadFileURL` gets this same blanket
  redaction from every uncaught error, while loading byte-identical content
  over `http://127.0.0.1` (a throwaway local test server used only for this
  diagnostic) gets full detail every time. This is WebKit's standard
  cross-origin error-redaction policy applying unconditionally to `file://`
  content — not a manimani bug, not something fixable from the HTML/JS side
  (a `crossorigin` attribute doesn't help; even same-document inline script
  was redacted). Fixed by dropping `loadFileURL` entirely: macOS/iOS
  AppDelegate templates now register a `WKURLSchemeHandler`
  (`KotobaWebBundleSchemeHandler`, `Sources/WebBundleSchemeHandler.swift`)
  that serves `Resources/WebBundle` under a custom `kotoba-webbundle://`
  scheme, which WebKit treats as a normal non-opaque origin — the same
  reason Capacitor/Ionic-style production WKWebView apps avoid `file://`.
  Verified against `local-manimani/mobile`'s real bundle on both a real
  macOS build and a real booted iOS Simulator install+launch: `window.
  onerror` now reports full detail. That full detail immediately surfaced
  the actual underlying bug in `local-manimani`'s bundle itself (not
  kotoba-shell's): `Could not find namespace kotoba-ui.theme.` — a missing
  scittle namespace script, filed separately as a `local-manimani` gap, not
  fixed here.
- **`doctor check`/`e2e check`/`device-farm check`: real, verified against
  real tools/devices on all three platforms, not just evidence-shaped
  data.** These were previously undocumented here (a reader had no way to
  tell whether they were live gates or aspirational scaffolding). Ran all
  three with `--strict` against real hardware/toolchains: macOS (local
  `pbcopy`/`pbpaste`/`curl`/`security`/`codesign`, `kotoba-shell-host-macos`
  smoke), iOS (a real booted Simulator via `xcrun simctl`), and Android (a
  real booted Emulator via `adb`, `system-images;android-34;google_apis;
  arm64-v8a` — the same one installed for the app-build verification
  above). All three targets came back `ready?: true` with zero missing
  required tools and a real `host-smoke` process execution (not a stub).
  `release dry-run`'s artifact/signature outputs are, by design, evidence
  placeholders rather than real binaries (confirmed by inspecting the
  output: a small text file, not a built `.app`) — that already matches
  what this README's Commands section says (`release dry-run` "writes
  target artifact evidence... without invoking platform stores"), so it
  isn't a gap, just a distinction worth being explicit about here too.
- **`contacts/list`/`calendar/list-events`: real, macOS-only.** Backed by
  AppleScript (`resources/kotoba/shell/selfhost/{contacts_list,
  calendar_list_events}.applescript`) through `bin/kotoba-shell-host-macos`,
  manually verified against real Contacts/Calendar data. There is no
  CLI-invokable equivalent on iOS/Android — that would need native
  Contacts/EventKit or ContactsContract/CalendarContract bridges compiled
  into an app, which don't exist yet. The provider catalog's
  `:required-targets` correctly says `[:macos]` only; it used to (wrongly)
  claim iOS/Android support with zero implementation behind it.
- **`webauthn/register`/`webauthn/assert`: real, macOS-only** (Touch ID/
  password-sheet passkey ceremony via a companion Swift helper). iOS/Android
  passkey providers are unimplemented.
- **Manifest schema is flat and shell-specific, not a general "app
  manifest" format.** `app scaffold`/`app build`/etc. expect exactly
  `:app/id`, `:app/name`, `:app/version`, `:ios/bundle-id`,
  `:android/application-id`, and the optional `:web/dist-dir` (a directory
  to embed as the app's web content; falls back to a placeholder page if
  omitted). A consumer with its own nested manifest convention (e.g.
  `local-manimani`'s `app.kotoba.edn`, which uses `:kotoba.app/id`/`:ui
  {...}`/`:capabilities {...}`) must translate it before calling this CLI;
  there is no schema auto-detection.
- **Not published anywhere.** No npm package, no Homebrew formula, no
  GitHub Release — `bin/kotoba-shell` is a thin `clojure -Sdeps` wrapper, so
  even a hypothetical `brew install`/`npm install -g` would still require a
  working Clojure CLI + JVM on the consumer's machine. Use it today as a
  sibling checkout with `KOTOBA_SHELL_BIN` pointed at `bin/kotoba-shell`, or
  a git dependency pinned to a specific commit (as `local-manimani/mobile`
  already does for the underlying `kotoba-lang/kotoba` crates).

## Commands

```sh
bin/kotoba-shell native-host check --target macos --json
bin/kotoba-shell native-host run --target macos --host-command /bin/echo --host-arg ok --json
bin/kotoba-shell native-host provider --target macos --provider-command clipboard/write-text --text ok --json
bin/kotoba-shell native-host provider --target macos --provider-command clipboard/read-text --json
bin/kotoba-shell surface check --target macos --json
bin/kotoba-shell surface commit --target macos --ops-edn '[[:dom/create-element 1 :main] [:dom/set-root 1]]' --json
bin/kotoba-shell app scaffold --target macos --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app check --target macos --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app build --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app run --target macos --manifest path/to/app.kotoba.edn --execute --json
bin/kotoba-shell app visual-test --target macos --manifest path/to/app.kotoba.edn --baseline test/visual/macos.png --actual target/visual/macos.png --execute --json
bin/kotoba-shell app kaizen --target macos --manifest path/to/app.kotoba.edn --baseline test/visual/macos.png --actual target/visual/macos.png --execute --json
bin/kotoba-shell policy check --target macos --provider-command clipboard/write-text --policy-edn '{:allow ["clipboard/text"] :deny []}' --json
bin/kotoba-shell release check --target macos --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0"}' --json
bin/kotoba-shell release evidence --target macos --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --json
bin/kotoba-shell release dry-run --target macos --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/release-smoke --json
bin/kotoba-shell release connect --target ios --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo"}' --app-store-key @secrets/app-store-connect-key.txt --json
bin/kotoba-shell release verify --target macos --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0"}' --output-dir target/kotoba-shell/release-smoke --json
bin/kotoba-shell release sign --target macos --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0"}' --sign-command /usr/bin/codesign --json
bin/kotoba-shell release submit --target ios --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo"}' --submit-command xcrun --json
bin/kotoba-shell updater publish --target macos --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0"}' --updater-feed target/kotoba-shell/release-smoke/macos/updater-feed.edn --json
bin/kotoba-shell store request --target ios --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo"}' --endpoint-url https://api.appstoreconnect.apple.com --auth-token-file secrets/app-store-connect.jwt --execute --json
bin/kotoba-shell store request --target ios --app-id dev.demo --app-name Demo --version 0.1.0 --ios-bundle-id dev.demo --endpoint-url https://api.appstoreconnect.apple.com --json
bin/kotoba-shell store status --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :android/application-id "dev.demo"}' --json
bin/kotoba-shell distribution check --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --json
bin/kotoba-shell distribution plan --target macos --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0"}' --plan target/kotoba-shell/distribution/plan.edn --write --json
bin/kotoba-shell api check --json
bin/kotoba-shell api freeze --api target/kotoba-shell/api/kotoba-shell-api.edn --write --json
bin/kotoba-shell api compat --api target/kotoba-shell/api/kotoba-shell-api.edn --json
bin/kotoba-shell plugin check --plugin-edn '{:plugin/id "demo.plugin" :plugin/version "0.1.0" :plugin/api-version 1 :plugin/providers []}' --json
bin/kotoba-shell plugin tauri-check --tauri-plugin-edn '{:tauri/plugin-id "tauri.clipboard" :tauri/commands ["clipboard/read-text"]}' --json
bin/kotoba-shell doctor check --target macos --json
bin/kotoba-shell device-farm check --target ios --target android --strict --json
bin/kotoba-shell device-farm schedule --target ios --target android --provider firebase-test-lab --cadence hourly --device-farm-command gcloud --device-farm-command-arg firebase --device-farm-command-arg test --device-farm-command-arg android --schedule target/kotoba-shell/device-farm/schedule.edn --run-log target/kotoba-shell/device-farm/run.edn --write --execute --json
bin/kotoba-shell doctor check --target ios --target android --strict --json
bin/kotoba-shell e2e check --target macos --json
bin/kotoba-shell e2e stack --json
bin/kotoba-shell e2e check --target ios --target android --strict --json
bin/kotoba-shell ui check --strict --json
bin/kotoba-shell ui smoke --strict --json
bin/kotoba-shell ui smoke --substrate browser --script smoke:visual --execute --strict --json
bin/kotoba-shell ui smoke --substrate browser --script smoke:webgpu --execute --strict --json
```

Supported target names are `macos`, `ios`, `android`, and `windows`.
macOS clipboard commands use `pbcopy`/`pbpaste` directly. Non-macOS provider
commands are routed through `--host-command` until each platform host runner is
implemented.

Default host runners are now bundled:

- `bin/kotoba-shell-host-macos`: local macOS process runner with clipboard,
  app-data fs, http fetch, notifications, and keychain command adapters.
- `bin/kotoba-shell-host-ios`: iOS simulator/device bridge through
  `xcrun simctl spawn booted`.
- `bin/kotoba-shell-host-android`: Android device bridge through `adb shell`.

`native-host run --target macos` uses the macOS runner by default. iOS and
Android expose real toolchain connection points; successful execution requires a
booted simulator for `xcrun simctl` or a connected Android device/emulator for
`adb`.

The shell does not require a Tauri-style system WebView. `surface check` records
`kotoba-lang/browser` as the browser/OS surface engine and `kotoba-lang/dom-gpu`
(renamed from `wasm-ui`) as the `kotoba:dom` UI substrate. Native hosts provide a display surface,
input events, lifecycle, and provider capabilities.

`app scaffold` generates minimal macOS, iOS, Android, and Windows native
project skeletons from the EDN app manifest. The generated projects carry the
Kotoba-native surface contract and can feed the release/sign/submit gates.
`app check` verifies that the expected scaffold files are present. `app build`
emits target-aware build plans for `xcodebuild`, Gradle, or MSBuild and runs
them only when `--execute` is present.

Provider calls pass through a small policy/audit runtime. Policies are EDN maps
with `:allow` and `:deny` entries matching provider commands, provider
capabilities, or `"*"`. Each policy check, provider denial, provider execution,
and surface commit returns a `kotoba.shell.audit.v0` record.

Release maturity is exposed through `release check`, `release evidence`,
`release dry-run`, and `release connect`. The check/evidence commands validate
target manifest requirements and return packaging, signing, updater, artifact,
and audit metadata. The dry-run command writes target artifact evidence,
dry-run signature evidence, and updater feed evidence without invoking platform
stores during local conformance runs. `release connect` is the production gate:
it verifies the target credentials and artifact paths required for Developer ID
notarization, App Store Connect, Google Play, and signed updater feeds.
`release sign` and `release submit` can run real signing/submission commands
when `--execute` is present; without it they return an auditable execution plan.
When a custom command is not supplied, the shell emits target-aware default
plans for macOS `codesign`/`notarytool`, iOS `xcodebuild`/App Store Connect,
Android `jarsigner`/Google Play, and Windows Authenticode. `updater publish`
does the same for signed updater feeds or store-backed release tracks.
`release verify` checks the artifact, signature evidence, and updater feed
digest chain before promotion.
`store request` and `store status` generate App Store Connect, Google Play,
Apple notarization, or Microsoft Store HTTP request evidence. They can execute
with the built-in Java HTTP client via `--execute --endpoint-url`, or hand the
request JSON to an external adapter with `--execute --http-command`.

`distribution check` combines production release connection readiness with
stable API and plugin compatibility expectations for store or channel
promotion. `api check` exposes the long-term stable command/data contract.
`plugin check` validates third-party provider manifests against
`kotoba.shell.plugin-api.v1` and the pointer+length/buffer/result host ABI.

`doctor check` reports host runner and platform toolchain readiness. Normal mode
returns warnings as data so local conformance can distinguish missing devices
from missing implementation. `--strict` turns missing required tools into a
failing gate for CI or device-farm profiles.

`e2e check` combines toolchain, surface, provider bridge, release metadata, and
host smoke readiness. The macOS path runs the bundled local host runner as a
smoke check. The iOS path runs the bundled `simctl` host runner and requires a
booted simulator. The Android path runs the bundled `adb` host runner and
requires a connected device or emulator. Nonstrict mode records missing devices
as warnings, while `--strict` is intended for CI/device-farm gates.

`device-farm check` is the continuous real-device E2E gate. It combines local
iOS/Android device readiness with an optional external device-farm command and
only runs that command when `--execute` is present.

`e2e stack` closes the load-bearing reference loop: aiueos makes a real grant
decision, kototama executes a checked-in Kotoba-compiled Wasm guest, shell
commits `kotoba:dom` operations, and kotobase appends then reads back one
correlated receipt. `resources/kotoba/shell/app/tauri_equivalent.kotoba` is the
Kotoba-owned application readiness source. `app run --execute` is the macOS T1
path for manifest applications: it evaluates the declared pure app entry,
passes its `kotoba:dom` operations to the shell-owned AppKit host, and records
the native lifecycle result. `--smoke` draws and closes the window for CI.

The macOS T1 native boundary is provided by
`bin/kotoba-shell-host-macos-window.swift`. It is a thin AppKit process: it
owns window/input/resize/lifecycle events while Kotoba owns app semantics.
Build and smoke it on macOS with `bin/kotoba-shell-build-macos-window` and
`target/kotoba-shell-host-macos-window --smoke`.

Windows has an explicit PowerShell host boundary at
`bin/kotoba-shell-host-windows.cmd` (delegating to `.ps1`); it emits a structured readiness event and
never falls back to a macOS or JVM process. Production Win32/WinUI providers
remain behind the same host contract.

`ui check` verifies that `kotoba-lang/dom-gpu` (internally keyed `:wasm-ui`,
unchanged to keep the `--substrate wasm-ui` CLI value stable) and
`kotoba-lang/browser` are present with the required source files and package scripts for the
Kotoba-native surface path. This is the readiness gate that replaces a
Tauri-style WebView dependency.

`ui smoke` exposes the concrete UI/browser smoke scripts as shell evidence.
Without `--execute` it returns the smoke plan and script readiness. With
`--execute` it runs the selected `npm run <script>` commands and records
exit/stdout/timeout data for CI. Browser smoke execution automatically starts
the local static server on port 8702, waits for it to accept HTTP traffic, and
then runs the selected WebGL/WebGPU smoke script.

## Relationship

- `kotoba-lang/shell`: authoritative shell adapter, provider catalog, native
  host contract, conformance tests.
- `kotoba-lang/kotoba`: compiler/runtime/Wasm language repo. It no longer owns
  shell commands.
- `aiueos`: OS/app orchestration model consumed by the shell provider catalog.
- `kotoba-safety-clj`: safety gate/policy layer consumed before privileged
  host/provider execution.
