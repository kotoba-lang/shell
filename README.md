# kotoba-shell

`kotoba-lang/shell` is the authority repo for the Kotoba shell adapter: the
Tauri-like native-host bridge, provider catalog, and desktop/mobile host
contracts.

The old `kotoba-lang/kotoba` CLI no longer keeps a compatibility shim for
`kotoba shell ...`; shell work and shell gates should call this CLI directly.

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
`kotoba-lang/browser` as the browser/OS surface engine and `kotoba-lang/wasm-ui`
as the `kotoba:dom` UI substrate. Native hosts provide a display surface,
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

`ui check` verifies that `kotoba-lang/wasm-ui` and `kotoba-lang/browser` are
present with the required source files and package scripts for the
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
