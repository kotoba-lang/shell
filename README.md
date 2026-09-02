# kotoba-shell

`kotoba-lang/shell` is the authority repo for the Kotoba shell adapter: the
Tauri-like native-host bridge, provider catalog, and desktop/mobile host
contracts.

The old `kotoba-lang/kotoba` CLI no longer keeps a compatibility shim for
`kotoba shell ...`; shell work and shell gates should call this CLI directly.

## How to start now

Working operator start is compile + `instantiateKotoba`. There is no
`kotoba -M` and no `clojure -M` / `clj -M` start path. `:run` is gone.

```sh
kotoba compile kotoba/launcher.kotoba --target wasm --output target/kotoba/launcher.wasm --json
kotoba compile kotoba/launcher.kotoba --target web --output target/kotoba/launcher.mjs --json
sh scripts/kotoba-compile.sh
sh scripts/kotoba-run.sh
bin/kotoba-shell
```

`kotoba run kotoba/launcher.kotoba` is the intended public command. On
Release CLI it is `kotoba/runtime-rejected` (typed forms) until the CLI
accepts this guest. That is a named CLI source-run gap, not a working
start. Do not treat `exec kotoba run` as the live operator path.

Language pin is `kotoba-lang@245493fc68404e0ae0b0cfb426f3881fdba64b5f`
(green main test run 33620750254). See `kotoba-lang.pin.edn`.
Previous pin `48d7d3cb` (murakumo#359) is not current for this repo.
Emit CLI is Release kotoba; the pin is the language SHA. HOLD/Release is
not lifted.

`resources/kotoba/shell/app/tauri_equivalent.kotoba` is an app-readiness
program, not this launcher. Do not compile it as a stand-in kexe.

Native sealed kexe (only if/when `bin/amu` is on aarch64-macos). Not short
`aarch64`. Not a host OS binary:

```sh
bin/amu check kotoba/launcher.kotoba --jvm-free
bin/amu compile kotoba/launcher.kotoba --target aarch64-macos --jvm-free --output launcher.kexe
bin/amu verify launcher.kexe
```

Linux kexe-verify is HOLD (`scripts/amu-native.sh` exits 78, not fake `:ok`).
Host scripts (`bin/kotoba-shell-host-*`) stay OS binaries. Do not wrap
`KotobaShellBridge.java` (Android WebView) or Datalevin/LMDB JNI.

Leftover JVM library tests live in `.github/workflows/leftover-jvm.yml`
(`workflow_dispatch` only, labeled leftover). Default CI is job
`kotoba-operator`.

## Status

Everything below this section is a command reference for the full CLI
surface; not every command is equally mature. This section says plainly what
is real and verified today versus still aspirational, so a consumer doesn't
have to rediscover the gap by hitting it (as `local-manimani`'s own
integration did more than once).

- **`app build` and `app package` are different claims and were for a long
  time confused for one.** `app build` is the development loop: `-sdk
  iphonesimulator`, `assembleDebug`, `CODE_SIGNING_ALLOWED: NO`. Everything
  it produces runs on the machine that built it and nowhere else, so "iOS and
  Android build" was true while "iOS and Android ship" was not. `app package`
  is the other half — device SDK, Release configuration, `xcodebuild archive`
  plus `-exportArchive`, `gradle bundleRelease` — and it refuses rather than
  emitting an unsigned artifact, because an `.ipa` nobody can install and an
  `.aab` Google Play rejects both exit 0.
  **Verified 2026-08-07 against real signing identities**, not by plan
  inspection: a macOS `.app` signed `Apple Development: … (U7W6HNNJCS)` with
  `flags=0x10000(runtime)` that passes `codesign --verify --deep --strict`; a
  device `.ipa` carrying an auto-provisioned `iOS Team Provisioning Profile`;
  and an `.aab` that `jarsigner -verify` reports as verified. Signing turns on
  only when the manifest names a team (`:apple/team-id` / `--team-id`) or
  Gradle finds a keystore; absence keeps the unsigned simulator path this repo
  has always had.
  **Not verified: an actual upload.** `store request` / `release submit` can
  execute, but nothing here has been through App Store Connect review or a
  Play track.
- **Providers now ship inside the app.** `native-host provider` reaches iOS
  and Android through `xcrun simctl spawn` and `adb shell` — a developer's
  machine driving an attached device — so before this the provider catalog's
  `:required-targets [:macos :ios :android]` described the CLI and not any
  build a user could hold. `app scaffold` now compiles
  `KotobaShellBridge.swift` (`WKScriptMessageHandlerWithReply`) and
  `KotobaShellBridge.java` (`@JavascriptInterface` + `evaluateJavascript`)
  into the app, reachable from the web bundle as `window.kotobaShell.invoke`
  and from ClojureScript as `kotoba.shell.client`. Ten commands: clipboard,
  app-data `fs`, keychain, `http/fetch`, `notify/show`.
  Policy is enforced **in the app**: `app scaffold` writes the same
  `:allow`/`:deny` map as `Resources/kotoba-shell-policy.json`, both native
  halves reproduce `policy-decision` clause for clause, and a missing policy
  asset denies everything. A test asserts the two evaluators agree for every
  command rather than trusting that they do.
  **Verified on a booted iOS Simulator and a booted Android Emulator**
  (`test/web/bridge-smoke/`): clipboard round-trip, `fs` write/append/read
  round-trip, a `..` path escape refused, `keychain/write-text` refused by
  policy, a real 200 from `example.com`, and — on Android, where the
  permission is grantable headlessly — a posted notification.
  **`contacts`, `calendar` and `webauthn` are still macOS-only** and are
  deliberately absent from the bridge; each needs a real per-platform
  integration, and listing them would repeat the mistake the catalog already
  had to correct.
- **Default CI is `kotoba compile` / guest-run, not `clojure -M`.** Job
  `kotoba-operator` in `.github/workflows/ci.yml` installs Release kotoba
  CLI, compiles `kotoba/launcher.kotoba` to wasm+web, checks files on disk,
  then guest-runs via `instantiateKotoba`. Linux kexe-verify is HOLD (exit
  78). Leftover JVM tests and leftover app-scaffold smoke live in
  `.github/workflows/leftover-jvm.yml` (`workflow_dispatch` only). GitHub
  Actions was previously disabled for this repository
  (`{"enabled": false}`, ADR-2607300900); the workflow files are the
  reference for how these builds are exercised.

- **`app scaffold`/`app build` (macOS, iOS): real, verified.** `app scaffold`
  generates an XcodeGen `project.yml` and runs `xcodegen generate` itself
  (not a hand-written `project.pbxproj`), producing a project that
  `xcodebuild` actually builds — confirmed end to end, including installing
  and launching the built app on a real booted iOS Simulator and
  screenshotting real WKWebView-rendered content. Leftover JVM
  (`leftover-jvm.yml`, workflow_dispatch only) still has a leftover
  `app scaffold` + `app build --execute` smoke for both targets. Default
  CI does not run that path.
- **`app scaffold`/`app build` (Android): real, verified end to end,
  including a real booted emulator install+launch.** Generates a real
  WebView-hosting `MainActivity.java` and a Gradle project that `gradle
  assembleDebug` builds into a real `app-debug.apk`. Leftover JVM
  (`leftover-jvm.yml`, workflow_dispatch only) still has a leftover
  Android `app scaffold` + `app build --execute` smoke. Default CI does
  not run that path. **Real gotcha found while wiring this up**: Gradle 9.x lets AGP's
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
  one. **Correction (2026-08-07)**: this bullet used to claim that Android's
  `WebView` logs full JS console output to `adb logcat` by default, with no
  `WebChromeClient.onConsoleMessage` override needed, and called that
  "strictly better than WKWebView's". Re-measured on WebView 113.0.5672.136
  (the `android-34;google_apis;arm64-v8a` image) it produced **no
  `[INFO:CONSOLE]` lines at all** with no `WebChromeClient` set, which cost a
  debugging pass on the bridge smoke test. The scaffold now installs a
  `WebChromeClient` that forwards console output to logcat under the tag
  `kotoba-shell`, and only in a debuggable build — a release app should not
  write its page's console output to a log every other app can read.
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
  GitHub Release. `bin/kotoba-shell` is compile + `instantiateKotoba`
  (`scripts/kotoba-compile.sh` then `scripts/kotoba-run.sh`). `kotoba run
  kotoba/launcher.kotoba` is the intended public command and a CLI
  source-run gap (`kotoba/runtime-rejected` on Release CLI) until the CLI
  accepts this guest. Leftover JVM library dispatch
  (`clojure -M -m kotoba.shell.launcher`) is not a start path and has no
  `:run` alias. Use it today as a sibling checkout with the Release kotoba
  CLI on PATH, or a git dependency pinned to a specific commit (as
  `local-manimani/mobile` already does for the underlying
  `kotoba-lang/kotoba` crates).

## Commands

```sh
kotoba compile kotoba/launcher.kotoba --target wasm --output target/kotoba/launcher.wasm --json
kotoba compile kotoba/launcher.kotoba --target web --output target/kotoba/launcher.mjs --json
sh scripts/kotoba-compile.sh
sh scripts/kotoba-run.sh
bin/kotoba-shell
# intended public command (CLI source-run gap until the CLI accepts this guest):
# kotoba run kotoba/launcher.kotoba
# leftover JVM library (not a start path; no :run alias).
# Guest treats native-host / app / store / doctor / e2e as host-listen HOLD.
# clojure -M -m kotoba.shell.launcher native-host check --target macos --json
# Remaining leftover-library subcommands used to ride bin/kotoba-shell when
# that wrapper was `exec clojure -M -m kotoba.shell.launcher`.
bin/kotoba-shell native-host provider --target macos --provider-command clipboard/write-text --text ok --json
bin/kotoba-shell native-host provider --target macos --provider-command clipboard/read-text --json
bin/kotoba-shell native-host provider --target macos --provider-command calendar/list-events --host-arg --from --host-arg 2026-07-01T00:00:00Z --host-arg --to --host-arg 2026-08-01T00:00:00Z --json
bin/kotoba-shell surface check --target macos --json
bin/kotoba-shell surface commit --target macos --ops-edn '[[:dom/create-element 1 :main] [:dom/set-root 1]]' --json
bin/kotoba-shell app scaffold --target macos --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app check --target macos --target ios --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :ios/bundle-id "dev.demo" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app build --target android --manifest-edn '{:app/id "demo" :app/name "Demo" :app/version "0.1.0" :android/application-id "dev.demo"}' --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app package --target ios --execute --manifest path/to/app.kotoba.edn --team-id U7W6HNNJCS --ios-export-method app-store-connect --output-dir target/kotoba-shell/app --json
bin/kotoba-shell app package --target android --execute --manifest path/to/app.kotoba.edn --output-dir target/kotoba-shell/app --json   # KOTOBA_SHELL_KEYSTORE=… or keystore.properties
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

## Mangaka Local Studio

The repository includes a Kotoba-native local control surface for the Mangaka
EDN → image generation → SAM layer separation → Genko composition → quality
loop. The app implementation is owned by `gftdcojp/ai-gftd-mangaka`, generated
assets by `gftdcojp/mangaka-data`, and reusable page/Genko/QA behavior by the
corresponding `kotoba-lang/kami-mangaka-*` repositories. Start the Mangaka API
on `127.0.0.1:8088`, then run:

```sh
bin/kotoba-shell app run --target macos \
  --manifest mangaka.app.kotoba.edn --execute --rebuild-window
```

Set `MANGAKA_WORKSPACE` when the shell and Mangaka repositories do not share a
west workspace root. `MANGAKA_API_BASE` may change the port, but native action
dispatch remains restricted to `localhost` and `127.0.0.1`.

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

The shell assumes WebKit as its rendering engine on macOS and iOS. Applications
ship a web bundle and run in WKWebView; ClojureScript, re-frame, WebGL/WebGPU,
and ordinary web UI libraries are first-class. `kotoba:dom` remains a
compatibility/input ABI for older surfaces, not a competing default renderer.
Native hosts provide the WebKit window, lifecycle, custom-scheme bundle
delivery, diagnostics, and provider capabilities.

### Typed text input actions

Apps bind a field and an explicit submit action with `kotoba.shell.input/field`
and `kotoba.shell.input/submit`. The native host owns IME/editing state and emits
the current string as the action event's `value`; the app owns validation,
persistence, and effects. Input changes alone never invoke an app action.

While an explicit action is running, the app runtime re-evaluates and commits
the read-only surface every `KOTOBA_SHELL_ACTION_REFRESH_SECONDS` (default
`0.5`). This streams durable ledger/checkpoint progress without exposing a
mutable token buffer. Set it to `0` to disable action pulses.

### The in-app provider bridge

A scaffolded macOS, iOS or Android app carries its own provider
implementations, so a distributed build reaches native capabilities without a
CLI, a JVM, or a developer's machine attached over `simctl`/`adb`:

```js
const reply = await window.kotobaShell.invoke("clipboard/write-text", { text: "ok" });
// {schema: "kotoba.shell.bridge.v0", ok: true, value: {...}, audit: {...}}
```

```clojure
(require '[kotoba.shell.client :as shell])
(-> (shell/fs-write! "notes/today.edn" (pr-str state))
    (.then #(when-not (shell/ok? %) (handle (:error %)))))
```

`invoke` never rejects: a denied command, a missing provider and a provider
failure all resolve with `ok: false` and a reason, and in a plain browser
`available` is false so one bundle runs both on a device and under
`shadow-cljs watch`. Replies carry the same `kotoba.shell.audit.v0` record the
CLI prints.

This is what makes a ClojureScript app shippable to a phone. An app whose
interface is served by a local JVM process — `cloud-itonami-app` is the
example in this workspace — cannot follow, and no packaging work changes that:
there is no JVM on iOS or Android. An app whose surface is a web bundle has
nothing to port.

Android's WebView serves the bundle from a `WebViewAssetLoader` origin
(`https://appassets.androidplatform.net/assets/…`) rather than
`file:///android_asset/`. An opaque origin has no DOM storage, no IndexedDB,
and nothing for document-start script injection to match — the same class of
problem the Apple side already fixed by leaving `file://` for a custom scheme.

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

### Tamaki Observatory

`apps/tamaki-observer` is a read-only native projection of Tamaki's durable
event stream. It shows campaign bounds and status, patch/integration/failure
counts, and recent AgentRuns. The shell runtime re-renders when
`.tamaki/events.edn` changes:

```sh
bin/kotoba-shell-tamaki-observer
```

The default `Current work`, `Flow`, and `Finance` surfaces use the same
light-mode Digital Agency Design System contract as `cloud-itonami-app`.
Components and vendored tokens come from the existing shared
`kotoba-lang/jp-go-digital-design-system` repository; Observatory-specific
layout remains local. `3D ecosystem` is a separate, on-demand Three.js view,
so the full Git tree and living-garden renderer do not consume WebGL resources
while the operational dashboard is open.

Set `TAMAKI_PROJECT_DIR` or `TAMAKI_STATE_DIR` when observing another checkout.
The observer never controls or mutates the agent loop; authority remains with
Tamaki and Radicle.

- `kotoba-lang/shell`: authoritative shell adapter, provider catalog, native
  host contract, conformance tests.
- `kotoba-lang/kotoba`: compiler/runtime/Wasm language repo. It no longer owns
  shell commands.
- `aiueos`: OS/app orchestration model consumed by the shell provider catalog.
- `kotoba-safety-clj`: safety gate/policy layer consumed before privileged
  host/provider execution.
