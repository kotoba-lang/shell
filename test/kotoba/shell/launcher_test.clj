(ns kotoba.shell.launcher-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.shell.launcher :as launcher]))

(defn with-test-http-server
  [handler f]
  (let [server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0)
                0)
        port (-> server .getAddress .getPort)
        http-handler (reify com.sun.net.httpserver.HttpHandler
                       (handle [_ exchange]
                         (handler exchange)))]
    (.createContext server "/" http-handler)
    (.start server)
    (try
      (f (str "http://127.0.0.1:" port))
      (finally
        (.stop server 0)))))

(deftest native-host-check-uses-shell-owned-contracts
  (let [macos-result (launcher/dispatch ["native-host" "check" "--target" "macos" "--json"])
        ios-result (launcher/dispatch ["native-host" "check" "--target" "ios" "--json"])
        android-result (launcher/dispatch ["native-host" "check" "--target" "android" "--json"])
        unknown-result (launcher/dispatch ["native-host" "check" "--target" "beos"])]
    (is (:kotoba.cli/ok? macos-result))
    (is (= "kotoba-lang/shell"
           (get-in macos-result [:kotoba.cli/data :kotoba.shell/authority])))
    (is (false? (get-in macos-result [:kotoba.cli/data :kotoba.shell/deprecated-shim?])))
    (is (= :shell/native-host-ready (:kotoba.cli/code macos-result)))
    (is (= 15 (get-in macos-result [:kotoba.cli/data :kotoba.shell/provider-command-count])))
    (is (string? (get-in macos-result [:kotoba.cli/data :kotoba.shell/default-host-command])))
    (is (= :process (get-in macos-result [:kotoba.cli/data :kotoba.shell/default-host-runner :kind])))
    (is (= :simctl (get-in ios-result [:kotoba.cli/data :kotoba.shell/default-host-runner :kind])))
    (is (= :adb (get-in android-result [:kotoba.cli/data :kotoba.shell/default-host-runner :kind])))
    (is (= 8 (get-in ios-result [:kotoba.cli/data :kotoba.shell/capability-gate-count])))
    (is (= 53766 (get-in android-result [:kotoba.cli/data :kotoba.shell/native-host-exports
                                         "native-command-surface-digest"])))
    (is (false? (:kotoba.cli/ok? unknown-result)))
    (is (= :shell/unknown-target (:kotoba.cli/code unknown-result)))))

(deftest native-host-run-connects-to-host-process
  (let [default-result (launcher/dispatch ["native-host" "run"
                                           "--target" "macos"
                                           "--json"])
        result (launcher/dispatch ["native-host" "run"
                                   "--target" "macos"
                                   "--host-command" "/bin/echo"
                                   "--host-arg" "kotoba-shell-host-ok"
                                   "--json"])
        missing-result (launcher/dispatch ["native-host" "run" "--target" "windows"])]
    (is (:kotoba.cli/ok? default-result))
    (is (= true (get-in default-result [:kotoba.cli/data :kotoba.shell/default-host-runner?])))
    (is (= "kotoba-shell-host-macos ready target=macos\n"
           (get-in default-result [:kotoba.cli/data :kotoba.shell/stdout])))
    (is (:kotoba.cli/ok? result))
    (is (= :shell/native-host-ran (:kotoba.cli/code result)))
    (is (= true (get-in result [:kotoba.cli/data :kotoba.shell/native-host-connected?])))
    (is (= "kotoba-shell-host-ok\n"
           (get-in result [:kotoba.cli/data :kotoba.shell/stdout])))
    (is (false? (:kotoba.cli/ok? missing-result)))
    (is (= :shell/native-host-command-required (:kotoba.cli/code missing-result)))))

(deftest macos-clipboard-provider-runs-through-native-host
  (let [original (:stdout (launcher/run-native-host-command "/usr/bin/pbpaste" []))]
    (try
      (let [write-result (launcher/dispatch ["native-host" "provider"
                                             "--target" "macos"
                                             "--provider-command" "clipboard/write-text"
                                             "--text" "kotoba-shell-clipboard-ok"
                                             "--json"])
            read-result (launcher/dispatch ["native-host" "provider"
                                            "--target" "macos"
                                            "--provider-command" "clipboard/read-text"
                                            "--json"])
            ios-missing-runner (launcher/dispatch ["native-host" "provider"
                                                   "--target" "ios"
                                                   "--provider-command" "clipboard/read-text"
                                                   "--host-command" "/bin/echo"])
            unknown-provider (launcher/dispatch ["native-host" "provider"
                                                 "--target" "macos"
                                                 "--provider-command" "wat/nope"])]
        (is (:kotoba.cli/ok? write-result))
        (is (= :shell/provider-ran (:kotoba.cli/code write-result)))
        (is (:kotoba.cli/ok? read-result))
        (is (= "kotoba-shell-clipboard-ok"
               (get-in read-result [:kotoba.cli/data :kotoba.shell/provider-output])))
        (is (:kotoba.cli/ok? ios-missing-runner))
        (is (= :shell/provider-ran (:kotoba.cli/code ios-missing-runner)))
        (is (false? (:kotoba.cli/ok? unknown-provider)))
        (is (= :shell/provider-command-unknown (:kotoba.cli/code unknown-provider))))
      (finally
        (launcher/run-native-host-command "/usr/bin/pbcopy" [] original)))))

(deftest adapter-check-reports-provider-catalog
  (testing "provider catalog remains shell-owned and target-filtered"
    (let [result (launcher/dispatch ["adapter" "check" "--target" "android" "--json"])]
      (is (:kotoba.cli/ok? result))
      (is (= :android (get-in result [:kotoba.cli/data :kotoba.shell/target])))
      (is (= 8 (get-in result [:kotoba.cli/data :kotoba.shell/provider-count]))))))

(deftest surface-host-uses-browser-and-wasm-ui-without-webview
  (let [check-result (launcher/dispatch ["surface" "check" "--target" "macos" "--json"])
        commit-result (launcher/dispatch ["surface" "commit"
                                          "--target" "macos"
                                          "--ops-edn" "[[:dom/create-element 1 :main] [:dom/set-root 1]]"
                                          "--json"])
        invalid-result (launcher/dispatch ["surface" "commit"
                                           "--target" "macos"
                                           "--ops-edn" "{:not :ops}"])]
    (is (:kotoba.cli/ok? check-result))
    (is (= :shell/surface-ready (:kotoba.cli/code check-result)))
    (is (false? (get-in check-result [:kotoba.cli/data :kotoba.shell/webview-required?])))
    (is (= "kotoba-lang/browser"
           (get-in check-result [:kotoba.cli/data :kotoba.shell/browser-engine])))
    (is (= "kotoba-lang/dom-gpu"
           (get-in check-result [:kotoba.cli/data :kotoba.shell/ui-substrate])))
    (is (= :native-surface
           (get-in check-result [:kotoba.cli/data :kotoba.shell/surface-host :kind])))
    (is (:kotoba.cli/ok? commit-result))
    (is (= :shell/surface-committed (:kotoba.cli/code commit-result)))
    (is (= 2 (get-in commit-result [:kotoba.cli/data :kotoba.shell/ops-count])))
    (is (= :surface/committed
           (get-in commit-result [:kotoba.cli/data :kotoba.shell/audit :audit/event])))
    (is (false? (:kotoba.cli/ok? invalid-result)))
    (is (= :shell/surface-ops-invalid (:kotoba.cli/code invalid-result)))))

(deftest provider-policy-and-audit-gate-host-capabilities
  (let [allowed-policy "{:allow [\"clipboard/text\"] :deny []}"
        denied-policy "{:allow [\"*\"] :deny [\"clipboard/write-text\"]}"
        allowed (launcher/dispatch ["policy" "check"
                                    "--target" "macos"
                                    "--provider-command" "clipboard/write-text"
                                    "--policy-edn" allowed-policy])
        denied (launcher/dispatch ["policy" "check"
                                   "--target" "macos"
                                   "--provider-command" "clipboard/write-text"
                                   "--policy-edn" denied-policy])
        provider-denied (launcher/dispatch ["native-host" "provider"
                                            "--target" "macos"
                                            "--provider-command" "clipboard/write-text"
                                            "--text" "denied"
                                            "--policy-edn" denied-policy])]
    (is (:kotoba.cli/ok? allowed))
    (is (= :shell/policy-allowed (:kotoba.cli/code allowed)))
    (is (= "clipboard/text"
           (get-in allowed [:kotoba.cli/data :kotoba.shell/policy-decision :capability])))
    (is (false? (:kotoba.cli/ok? denied)))
    (is (= :shell/policy-denied (:kotoba.cli/code denied)))
    (is (false? (:kotoba.cli/ok? provider-denied)))
    (is (= :shell/provider-denied (:kotoba.cli/code provider-denied)))
    (is (= :provider/denied
           (get-in provider-denied [:kotoba.cli/data :kotoba.shell/audit :audit/event])))))

(deftest macos-fs-provider-runs-through-default-host
  (let [file (doto (java.io.File/createTempFile "kotoba-shell-fs" ".txt")
               (.deleteOnExit))
        path (.getPath file)
        write-result (launcher/dispatch ["native-host" "provider"
                                         "--target" "macos"
                                         "--provider-command" "fs/write-text"
                                         "--text" "kotoba-fs-ok"
                                         "--host-arg" "--path"
                                         "--host-arg" path])
        read-result (launcher/dispatch ["native-host" "provider"
                                        "--target" "macos"
                                        "--provider-command" "fs/read-text"
                                        "--host-arg" "--path"
                                        "--host-arg" path])]
    (is (:kotoba.cli/ok? write-result))
    (is (= :shell/provider-ran (:kotoba.cli/code write-result)))
    (is (= "fs/app-data"
           (get-in write-result [:kotoba.cli/data :kotoba.shell/provider-capability])))
    (is (:kotoba.cli/ok? read-result))
    (is (= "kotoba-fs-ok"
           (get-in read-result [:kotoba.cli/data :kotoba.shell/provider-output])))
    (is (= :provider/ran
           (get-in read-result [:kotoba.cli/data :kotoba.shell/audit :audit/event])))))

(deftest macos-webauthn-provider-is-macos-only-and-gated-by-default
  ;; ASAuthorizationController (real Touch ID / passkey ceremony) needs a
  ;; live interactive session and cannot be exercised in an automated test,
  ;; so this only verifies the dispatch/catalog/policy plumbing -- exactly
  ;; how the other native-only behavior (real biometric hardware) is out of
  ;; scope for the rest of this suite too. A fake --host-command stands in
  ;; for the real (never built in CI) native passkey helper.
  (let [explicit-allow-policy "{:allow [\"webauthn/passkey\"] :deny []}"
        register-with-fake-host
        (launcher/dispatch ["native-host" "provider"
                            "--target" "macos"
                            "--provider-command" "webauthn/register"
                            "--host-command" "/bin/echo"
                            "--host-arg" "fake-webauthn-ok"
                            "--policy-edn" explicit-allow-policy])
        ios-unknown (launcher/dispatch ["native-host" "provider"
                                        "--target" "ios"
                                        "--provider-command" "webauthn/register"
                                        "--host-command" "/bin/echo"
                                        "--policy-edn" explicit-allow-policy])
        windows-unknown (launcher/dispatch ["native-host" "provider"
                                            "--target" "windows"
                                            "--provider-command" "webauthn/assert"
                                            "--policy-edn" explicit-allow-policy])
        default-policy-denied (launcher/dispatch ["native-host" "provider"
                                                  "--target" "macos"
                                                  "--provider-command" "webauthn/register"
                                                  "--host-command" "/bin/echo"])]
    (is (:kotoba.cli/ok? register-with-fake-host))
    (is (= :shell/provider-ran (:kotoba.cli/code register-with-fake-host)))
    (is (= "webauthn/passkey"
           (get-in register-with-fake-host [:kotoba.cli/data :kotoba.shell/provider-capability])))
    (is (str/includes? (get-in register-with-fake-host [:kotoba.cli/data :kotoba.shell/stdout])
                       "fake-webauthn-ok"))
    (is (false? (:kotoba.cli/ok? ios-unknown)))
    (is (= :shell/provider-command-unknown (:kotoba.cli/code ios-unknown))
        "webauthn requires :macos in :required-targets, even when the policy allows it")
    (is (false? (:kotoba.cli/ok? windows-unknown)))
    (is (= :shell/provider-command-unknown (:kotoba.cli/code windows-unknown)))
    (is (false? (:kotoba.cli/ok? default-policy-denied))
        "webauthn/* must not be in the default allow-list, same as keychain/*")
    (is (= :shell/provider-denied (:kotoba.cli/code default-policy-denied)))))

(deftest macos-webauthn-provider-allowed-with-explicit-policy
  (let [policy "{:allow [\"webauthn/passkey\"] :deny []}"
        allowed (launcher/dispatch ["native-host" "provider"
                                    "--target" "macos"
                                    "--provider-command" "webauthn/assert"
                                    "--host-command" "/bin/echo"
                                    "--host-arg" "fake-assert-ok"
                                    "--policy-edn" policy])]
    (is (:kotoba.cli/ok? allowed))
    (is (str/includes? (get-in allowed [:kotoba.cli/data :kotoba.shell/stdout]) "fake-assert-ok"))))

(deftest webauthn-provider-declares-a-longer-timeout-than-instant-providers
  ;; A real passkey ceremony needs a human to notice and respond to a system
  ;; Touch ID / password sheet, which routinely exceeds the 10s default used
  ;; by every other (near-instant) provider.
  (is (= 120 (launcher/provider-timeout-seconds :macos "webauthn/register")))
  (is (= 120 (launcher/provider-timeout-seconds :macos "webauthn/assert")))
  (is (= launcher/default-provider-timeout-seconds
         (launcher/provider-timeout-seconds :macos "clipboard/read-text")))
  (is (= launcher/default-provider-timeout-seconds
         (launcher/provider-timeout-seconds :macos "unknown/command"))))

(deftest release-check-and-evidence-cover-packaging-signing-updater
  (let [macos-manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\"}"
        mobile-manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\" :android/application-id \"dev.kotoba.demo\"}"
        dry-run-dir (.getPath (doto (io/file (System/getProperty "java.io.tmpdir")
                                               (str "kotoba-shell-release-" (System/nanoTime)))
                                  (.mkdirs)))
        macos-ready (launcher/dispatch ["release" "check"
                                        "--target" "macos"
                                        "--manifest-edn" macos-manifest])
        ios-blocked (launcher/dispatch ["release" "check"
                                        "--target" "ios"
                                        "--manifest-edn" macos-manifest])
        evidence (launcher/dispatch ["release" "evidence"
                                     "--target" "macos"
                                     "--target" "ios"
                                     "--target" "android"
                                     "--manifest-edn" mobile-manifest])
        dry-run (launcher/dispatch ["release" "dry-run"
                                    "--target" "macos"
                                    "--target" "ios"
                                    "--target" "android"
                                    "--manifest-edn" mobile-manifest
                                    "--output-dir" dry-run-dir])
        invalid-dry-run (launcher/dispatch ["release" "dry-run"
                                            "--target" "ios"
                                            "--manifest-edn" macos-manifest
                                            "--output-dir" dry-run-dir])]
    (is (:kotoba.cli/ok? macos-ready))
    (is (= :shell/release-ready (:kotoba.cli/code macos-ready)))
    (is (= ".app" (get-in macos-ready [:kotoba.cli/data :kotoba.shell/release-target :artifact])))
    (is (true? (get-in macos-ready [:kotoba.cli/data :kotoba.shell/packaging-ready?])))
    (is (true? (get-in macos-ready [:kotoba.cli/data :kotoba.shell/signing-ready?])))
    (is (true? (get-in macos-ready [:kotoba.cli/data :kotoba.shell/updater-ready?])))
    (is (false? (:kotoba.cli/ok? ios-blocked)))
    (is (= :shell/release-manifest-invalid (:kotoba.cli/code ios-blocked)))
    (is (= [:ios/bundle-id]
           (get-in ios-blocked [:kotoba.cli/data :kotoba.shell/missing-manifest-keys])))
    (is (:kotoba.cli/ok? evidence))
    (is (= :shell/release-evidence-ready (:kotoba.cli/code evidence)))
    (is (= 3 (get-in evidence [:kotoba.cli/data :kotoba.shell/release-ready-count])))
    (is (= "kotoba.shell.release-evidence.v0"
           (get-in evidence [:kotoba.cli/data :kotoba.shell/evidence-schema])))
    (is (:kotoba.cli/ok? dry-run))
    (is (= :shell/release-dry-run-ready (:kotoba.cli/code dry-run)))
    (is (= 3 (get-in dry-run [:kotoba.cli/data :kotoba.shell/release-ready-count])))
    (is (every? #(.isFile (io/file %))
                (get-in dry-run [:kotoba.cli/data :kotoba.shell/artifacts])))
    (is (every? #(.isFile (io/file %))
                (get-in dry-run [:kotoba.cli/data :kotoba.shell/signatures])))
    (is (every? #(.isFile (io/file %))
                (get-in dry-run [:kotoba.cli/data :kotoba.shell/updater-feeds])))
    (is (false? (:kotoba.cli/ok? invalid-dry-run)))
    (is (= :shell/release-dry-run-blocked (:kotoba.cli/code invalid-dry-run)))))

(deftest release-verify-checks_artifact_signature_and_updater_feed
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\"}"
        dry-run-dir (.getPath (doto (io/file (System/getProperty "java.io.tmpdir")
                                               (str "kotoba-shell-verify-" (System/nanoTime)))
                                  (.mkdirs)))
        dry-run (launcher/dispatch ["release" "dry-run"
                                    "--target" "macos"
                                    "--manifest-edn" manifest
                                    "--output-dir" dry-run-dir])
        artifact (get-in dry-run [:kotoba.cli/data :kotoba.shell/artifacts 0])
        signature (get-in dry-run [:kotoba.cli/data :kotoba.shell/signatures 0])
        feed (get-in dry-run [:kotoba.cli/data :kotoba.shell/updater-feeds 0])
        verified (launcher/dispatch ["release" "verify"
                                     "--target" "macos"
                                     "--manifest-edn" manifest
                                     "--artifact" artifact
                                     "--signature" signature
                                     "--updater-feed" feed])
        broken-feed (doto (java.io.File/createTempFile "kotoba-shell-broken-feed" ".edn")
                      (.deleteOnExit))
        _ (spit broken-feed (pr-str {:schema "kotoba.shell.updater-feed.v0"
                                     :target :macos
                                     :artifact "wrong.app"
                                     :artifact-digest "bad"
                                     :signature-file "wrong.sig.edn"}))
        blocked (launcher/dispatch ["release" "verify"
                                    "--target" "macos"
                                    "--manifest-edn" manifest
                                    "--artifact" artifact
                                    "--signature" signature
                                    "--updater-feed" (.getPath broken-feed)])]
    (is (:kotoba.cli/ok? dry-run))
    (is (:kotoba.cli/ok? verified))
    (is (= :shell/release-verified (:kotoba.cli/code verified)))
    (is (= 1 (get-in verified [:kotoba.cli/data :kotoba.shell/verified-count])))
    (is (= (get-in verified [:kotoba.cli/data :kotoba.shell/release-rows 0 :artifact-digest])
           (get-in verified [:kotoba.cli/data :kotoba.shell/release-rows 0 :signature-digest])
           (get-in verified [:kotoba.cli/data :kotoba.shell/release-rows 0 :feed-digest])))
    (is (false? (:kotoba.cli/ok? blocked)))
    (is (= :shell/release-verify-blocked (:kotoba.cli/code blocked)))))

(deftest app-scaffold-generates-native_project_skeletons
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\" :android/application-id \"dev.kotoba.demo\"}"
        output-dir (.getPath (doto (io/file (System/getProperty "java.io.tmpdir")
                                             (str "kotoba-shell-app-" (System/nanoTime)))
                                (.mkdirs)))
        scaffold (launcher/dispatch ["app" "scaffold"
                                     "--target" "macos"
                                     "--target" "ios"
                                     "--target" "android"
                                     "--manifest-edn" manifest
                                     "--output-dir" output-dir])
        check (launcher/dispatch ["app" "check"
                                  "--target" "macos"
                                  "--target" "ios"
                                  "--target" "android"
                                  "--manifest-edn" manifest
                                  "--output-dir" output-dir])
        invalid (launcher/dispatch ["app" "scaffold"
                                    "--target" "ios"
                                    "--manifest-edn" "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\"}"
                                    "--output-dir" output-dir])]
    (is (:kotoba.cli/ok? scaffold))
    (is (= :shell/app-scaffolded (:kotoba.cli/code scaffold)))
    (is (= 3 (get-in scaffold [:kotoba.cli/data :kotoba.shell/ready-count])))
    (is (every? pos-int?
                (map :file-count (get-in scaffold [:kotoba.cli/data :kotoba.shell/app-rows]))))
    ;; macOS/iOS: project.pbxproj を手書きせず xcodegen generate に作らせる方式
    ;; (native-render-pipeline、ADR-2607081015 の WKWebView 実用優先決定)。
    ;; Info.plist もその生成物(xcodegen の info.path 出力) — scaffold-files が
    ;; 直接書くファイル一覧には含まれないが、scaffold-target-row のパイプライン
    ;; 全体を通せば実在するようになる。SceneDelegate.swift は scene 無しの単純な
    ;; UIApplicationDelegate ライフサイクルにしたため、もう存在しない(意図的)。
    (is (.isFile (io/file output-dir "macos" "Info.plist")))
    (is (.isFile (io/file output-dir "macos" "KotobaShell.xcodeproj" "project.pbxproj")))
    (is (.isFile (io/file output-dir "macos" "Resources" "index.html")))
    (is (.isFile (io/file output-dir "ios" "Info.plist")))
    (is (.isFile (io/file output-dir "ios" "KotobaShell.xcodeproj" "project.pbxproj")))
    (is (.isFile (io/file output-dir "ios" "Resources" "index.html")))
    (is (.isFile (io/file output-dir "android" "app" "build.gradle")))
    (is (.isFile (io/file output-dir "android" "app" "src" "main" "assets" "index.html")))
    (is (:kotoba.cli/ok? check))
    (is (= :shell/app-ready (:kotoba.cli/code check)))
    (is (every? :ok? (get-in check [:kotoba.cli/data :kotoba.shell/app-rows])))
    (is (false? (:kotoba.cli/ok? invalid)))
    (is (= :shell/app-scaffold-blocked (:kotoba.cli/code invalid)))))

(deftest app-build-plans_and_executes_native_project_builds
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\" :android/application-id \"dev.kotoba.demo\"}"
        output-dir (.getPath (doto (io/file (System/getProperty "java.io.tmpdir")
                                             (str "kotoba-shell-build-" (System/nanoTime)))
                                (.mkdirs)))
        scaffold (launcher/dispatch ["app" "scaffold"
                                     "--target" "android"
                                     "--manifest-edn" manifest
                                     "--output-dir" output-dir])
        plan (launcher/dispatch ["app" "build"
                                 "--target" "android"
                                 "--manifest-edn" manifest
                                 "--output-dir" output-dir])
        executed (launcher/dispatch ["app" "build"
                                     "--target" "android"
                                     "--manifest-edn" manifest
                                     "--output-dir" output-dir
                                     "--build-command" "/bin/echo"
                                     "--build-command-arg" "built"
                                     "--execute"])]
    (is (:kotoba.cli/ok? scaffold))
    (is (:kotoba.cli/ok? plan))
    (is (= :shell/app-built (:kotoba.cli/code plan)))
    (is (= :gradle-assemble-debug
           (get-in plan [:kotoba.cli/data :kotoba.shell/app-rows 0 :build-step :platform-step])))
    (is (= false
           (get-in plan [:kotoba.cli/data :kotoba.shell/app-rows 0 :build-step :executed?])))
    (is (:kotoba.cli/ok? executed))
    (is (= true
           (get-in executed [:kotoba.cli/data :kotoba.shell/app-rows 0 :built?])))
    (is (= "built\n"
           (get-in executed [:kotoba.cli/data :kotoba.shell/app-rows 0 :build-step :stdout])))))

(deftest release-connect-gates-production-signing-updater-and_store_credentials
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\" :android/application-id \"dev.kotoba.demo\"}"
        app (doto (java.io.File/createTempFile "kotoba-shell" ".app") (.deleteOnExit))
        dmg (doto (java.io.File/createTempFile "kotoba-shell" ".dmg") (.deleteOnExit))
        ipa (doto (java.io.File/createTempFile "kotoba-shell" ".ipa") (.deleteOnExit))
        archive (doto (java.io.File/createTempFile "kotoba-shell" ".xcarchive") (.deleteOnExit))
        apk (doto (java.io.File/createTempFile "kotoba-shell" ".apk") (.deleteOnExit))
        aab (doto (java.io.File/createTempFile "kotoba-shell" ".aab") (.deleteOnExit))
        codesign-secret (doto (java.io.File/createTempFile "kotoba-codesign" ".txt")
                          (spit "Developer ID Application: Demo")
                          (.deleteOnExit))
        ready (launcher/dispatch ["release" "connect"
                                  "--target" "macos"
                                  "--target" "ios"
                                  "--target" "android"
                                  "--manifest-edn" manifest
                                  "--app-bundle" (.getPath app)
                                  "--dmg" (.getPath dmg)
                                  "--codesign-identity" (str "@" (.getPath codesign-secret))
                                  "--notary-profile" "kotoba-notary"
                                  "--updater-key" "updater-key"
                                  "--xcode-archive" (.getPath archive)
                                  "--ipa" (.getPath ipa)
                                  "--apple-team-id" "TEAM123"
                                  "--app-store-key" "asc-key"
                                  "--provisioning-profile" "profile"
                                  "--apk" (.getPath apk)
                                  "--aab" (.getPath aab)
                                  "--keystore" "keystore"
                                  "--keystore-alias" "release"
                                  "--play-service-account" "play-json"])
        blocked (launcher/dispatch ["release" "connect"
                                    "--target" "ios"
                                    "--manifest-edn" manifest])]
    (is (:kotoba.cli/ok? ready))
    (is (= :shell/release-connected (:kotoba.cli/code ready)))
    (is (= 3 (get-in ready [:kotoba.cli/data :kotoba.shell/release-ready-count])))
    (is (every? :credentials-ready?
                (get-in ready [:kotoba.cli/data :kotoba.shell/release-rows])))
    (is (= :file
           (get-in ready [:kotoba.cli/data :kotoba.shell/release-rows 0 :credentials 0 :source])))
    (is (every? :artifacts-ready?
                (get-in ready [:kotoba.cli/data :kotoba.shell/release-rows])))
    (is (false? (:kotoba.cli/ok? blocked)))
    (is (= :shell/release-connection-blocked (:kotoba.cli/code blocked)))
    (is (= false
           (get-in blocked [:kotoba.cli/data :kotoba.shell/release-rows 0 :credentials-ready?])))))

(deftest api-plugin-and-distribution-gates-report-stable-contracts
  (let [plugin "{:plugin/id \"demo.plugin\" :plugin/version \"0.1.0\" :plugin/api-version 1 :plugin/providers [{:id \"demo/clipboard\" :capability \"clipboard/text\" :commands [\"clipboard/read-text\"]}]}"
        incompatible-plugin "{:plugin/id \"demo.plugin\" :plugin/version \"0.1.0\" :plugin/api-version 99 :plugin/providers [{:id \"demo/bad\"}]}"
        api (launcher/dispatch ["api" "check"])
        compatible (launcher/dispatch ["plugin" "check" "--plugin-edn" plugin])
        incompatible (launcher/dispatch ["plugin" "check" "--plugin-edn" incompatible-plugin])
        distribution (launcher/dispatch ["distribution" "check"
                                         "--target" "ios"
                                         "--manifest-edn" "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\"}"])]
    (is (:kotoba.cli/ok? api))
    (is (= :shell/api-stable (:kotoba.cli/code api)))
    (is (= "kotoba.shell.api.v1"
           (get-in api [:kotoba.cli/data :kotoba.shell/api-schema])))
    (is (pos-int? (get-in api [:kotoba.cli/data :kotoba.shell/command-count])))
    (is (:kotoba.cli/ok? compatible))
    (is (= :shell/plugin-compatible (:kotoba.cli/code compatible)))
    (is (= 1 (get-in compatible [:kotoba.cli/data :kotoba.shell/provider-count])))
    (is (false? (:kotoba.cli/ok? incompatible)))
    (is (= :shell/plugin-incompatible (:kotoba.cli/code incompatible)))
    (is (= false (get-in incompatible [:kotoba.cli/data :kotoba.shell/api-compatible?])))
    (is (seq (get-in incompatible [:kotoba.cli/data :kotoba.shell/provider-problems])))
    (is (false? (:kotoba.cli/ok? distribution)))
    (is (= :shell/distribution-blocked (:kotoba.cli/code distribution)))
    (is (= true
           (get-in distribution [:kotoba.cli/data :kotoba.shell/distribution-rows 0 :device-e2e-required?])))))

(deftest long-term-api-tauri-plugin-distribution-and_device_farm_ops_are_machine_readable
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "kotoba-shell-ops-" (System/nanoTime)))
              (.mkdirs))
        api-file (.getPath (io/file dir "api.edn"))
        distribution-file (.getPath (io/file dir "distribution.edn"))
        schedule-file (.getPath (io/file dir "device-farm.edn"))
        run-log-file (.getPath (io/file dir "device-farm-run.edn"))
        manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\"}"
        app (doto (java.io.File/createTempFile "kotoba-shell" ".app") (.deleteOnExit))
        dmg (doto (java.io.File/createTempFile "kotoba-shell" ".dmg") (.deleteOnExit))
        freeze (launcher/dispatch ["api" "freeze" "--api" api-file "--write"])
        compat (launcher/dispatch ["api" "compat" "--api" api-file])
        tauri (launcher/dispatch ["plugin" "tauri-check"
                                  "--tauri-plugin-edn"
                                  "{:tauri/plugin-id \"tauri.clipboard\" :tauri/version \"1.0.0\" :tauri/commands [\"clipboard/read-text\" \"clipboard/write-text\"] :tauri/permissions [\"clipboard:default\"] :tauri/platforms [:macos :ios :android]}"])
        tauri-blocked (launcher/dispatch ["plugin" "tauri-check"
                                          "--tauri-plugin-edn"
                                          "{:tauri/plugin-id \"tauri.bad\" :tauri/commands [\"shell/open\"]}"])
        distribution (launcher/dispatch ["distribution" "plan"
                                         "--target" "macos"
                                         "--manifest-edn" manifest
                                         "--app-bundle" (.getPath app)
                                         "--dmg" (.getPath dmg)
                                         "--codesign-identity" "Developer ID Application: Demo"
                                         "--notary-profile" "kotoba-notary"
                                         "--updater-key" "updater-key"
                                         "--plan" distribution-file
                                         "--write"])
        schedule (launcher/dispatch ["device-farm" "schedule"
                                     "--target" "ios"
                                     "--target" "android"
                                     "--provider" "firebase-test-lab"
                                     "--cadence" "hourly"
                                     "--device-farm-command" "/bin/echo"
                                     "--device-farm-command-arg" "farm"
                                     "--schedule" schedule-file
                                     "--run-log" run-log-file
                                     "--execute"
                                     "--write"])]
    (is (:kotoba.cli/ok? freeze))
    (is (.isFile (io/file api-file)))
    (is (:kotoba.cli/ok? compat))
    (is (= :shell/api-compatible (:kotoba.cli/code compat)))
    (is (:kotoba.cli/ok? tauri))
    (is (= :shell/tauri-plugin-compatible (:kotoba.cli/code tauri)))
    (is (false? (:kotoba.cli/ok? tauri-blocked)))
    (is (= ["shell/open"]
           (get-in tauri-blocked [:kotoba.cli/data :kotoba.shell/unsupported-commands])))
    (is (:kotoba.cli/ok? distribution))
    (is (.isFile (io/file distribution-file)))
    (is (= "kotoba.shell.distribution-plan.v0"
           (:schema (read-string (slurp distribution-file)))))
    (is (:kotoba.cli/ok? schedule))
    (is (.isFile (io/file schedule-file)))
    (is (.isFile (io/file run-log-file)))
    (is (= "firebase-test-lab"
           (:provider (read-string (slurp schedule-file)))))
    (is (= true
           (get-in schedule [:kotoba.cli/data :kotoba.shell/executions 0 :executed?])))))

(deftest store-api-adapter-builds_and_executes_http_requests
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\" :android/application-id \"dev.kotoba.demo\"}"
        ipa (doto (java.io.File/createTempFile "kotoba-shell" ".ipa") (.deleteOnExit))
        archive (doto (java.io.File/createTempFile "kotoba-shell" ".xcarchive") (.deleteOnExit))
        apk (doto (java.io.File/createTempFile "kotoba-shell" ".apk") (.deleteOnExit))
        aab (doto (java.io.File/createTempFile "kotoba-shell" ".aab") (.deleteOnExit))
        ios (launcher/dispatch ["store" "request"
                                "--target" "ios"
                                "--manifest-edn" manifest
                                "--xcode-archive" (.getPath archive)
                                "--ipa" (.getPath ipa)
                                "--apple-team-id" "TEAM123"
                                "--app-store-key" "asc-key"
                                "--provisioning-profile" "profile"])
        android (launcher/dispatch ["store" "request"
                                    "--target" "android"
                                    "--operation" "status"
                                    "--manifest-edn" manifest
                                    "--apk" (.getPath apk)
                                    "--aab" (.getPath aab)
                                    "--keystore" "keystore"
                                    "--keystore-alias" "release"
                                    "--play-service-account" "play-json"])
        executed (launcher/dispatch ["store" "request"
                                     "--target" "ios"
                                     "--manifest-edn" manifest
                                     "--xcode-archive" (.getPath archive)
                                     "--ipa" (.getPath ipa)
                                     "--apple-team-id" "TEAM123"
                                     "--app-store-key" "asc-key"
                                     "--provisioning-profile" "profile"
                                     "--http-command" "/bin/cat"
                                     "--execute"])
        cli-manifest (launcher/dispatch ["store" "request"
                                         "--target" "ios"
                                         "--app-id" "dev.kotoba.cli"
                                         "--app-name" "Kotoba CLI"
                                         "--version" "1.2.3"
                                         "--ios-bundle-id" "dev.kotoba.cli"
                                         "--xcode-archive" (.getPath archive)
                                         "--ipa" (.getPath ipa)
                                         "--apple-team-id" "TEAM123"
                                         "--app-store-key" "asc-key"
                                         "--provisioning-profile" "profile"])
        blocked (launcher/dispatch ["store" "request"
                                    "--target" "ios"
                                    "--manifest-edn" manifest])]
    (is (:kotoba.cli/ok? ios))
    (is (= :app-store-connect
           (get-in ios [:kotoba.cli/data :kotoba.shell/store-rows 0 :request :provider])))
    (is (= "/v1/apps/dev.kotoba.demo/appStoreVersions"
           (get-in ios [:kotoba.cli/data :kotoba.shell/store-rows 0 :request :endpoint])))
    (is (:kotoba.cli/ok? android))
    (is (= "GET"
           (get-in android [:kotoba.cli/data :kotoba.shell/store-rows 0 :request :method])))
    (is (:kotoba.cli/ok? executed))
    (is (= true
           (get-in executed [:kotoba.cli/data :kotoba.shell/store-rows 0 :http-step :executed?])))
    (is (re-find #"app-store-connect"
                 (get-in executed [:kotoba.cli/data :kotoba.shell/store-rows 0 :http-step :stdout])))
    (is (:kotoba.cli/ok? cli-manifest))
    (is (= "/v1/apps/dev.kotoba.cli/appStoreVersions"
           (get-in cli-manifest [:kotoba.cli/data :kotoba.shell/store-rows 0 :request :endpoint])))
    (is (= "1.2.3"
           (get-in cli-manifest [:kotoba.cli/data :kotoba.shell/manifest :app/version])))
    (is (false? (:kotoba.cli/ok? blocked)))
    (is (= :shell/store-request-blocked (:kotoba.cli/code blocked)))))

(deftest store-api-adapter-can_execute_with_builtin_java_http_client
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\"}"
        ipa (doto (java.io.File/createTempFile "kotoba-shell" ".ipa") (.deleteOnExit))
        archive (doto (java.io.File/createTempFile "kotoba-shell" ".xcarchive") (.deleteOnExit))
        token-file (doto (java.io.File/createTempFile "kotoba-shell-token" ".txt")
                     (spit "store-token")
                     (.deleteOnExit))
        seen-auth (atom nil)]
    (with-test-http-server
      (fn [exchange]
        (reset! seen-auth (some-> (.getRequestHeaders exchange)
                                  (.getFirst "authorization")))
        (let [body (slurp (.getRequestBody exchange))
              response (.getBytes (str "{\"received\":" (pr-str (boolean (re-find #"app-store-connect" body))) "}")
                                  java.nio.charset.StandardCharsets/UTF_8)]
          (.sendResponseHeaders exchange 200 (count response))
          (with-open [out (.getResponseBody exchange)]
            (.write out response))))
      (fn [endpoint]
        (let [result (launcher/dispatch ["store" "request"
                                         "--target" "ios"
                                         "--manifest-edn" manifest
                                         "--xcode-archive" (.getPath archive)
                                         "--ipa" (.getPath ipa)
                                         "--apple-team-id" "TEAM123"
                                         "--app-store-key" "asc-key"
                                         "--provisioning-profile" "profile"
                                         "--endpoint-url" endpoint
                                         "--auth-token-file" (.getPath token-file)
                                         "--execute"])]
          (is (:kotoba.cli/ok? result))
          (is (= :java-http
                 (get-in result [:kotoba.cli/data :kotoba.shell/store-rows 0 :http-step :kind])))
          (is (= "Bearer store-token" @seen-auth))
          (is (= true
                 (get-in result [:kotoba.cli/data :kotoba.shell/store-rows 0 :http-step :auth-configured?])))
          (is (= 200
                 (get-in result [:kotoba.cli/data :kotoba.shell/store-rows 0 :http-step :status])))
          (is (re-find #"received"
                       (get-in result [:kotoba.cli/data :kotoba.shell/store-rows 0 :http-step :body]))))))))

(deftest release-sign-submit-and-device-farm-can_execute_external_steps
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\"}"
        app (doto (java.io.File/createTempFile "kotoba-shell" ".app") (.deleteOnExit))
        dmg (doto (java.io.File/createTempFile "kotoba-shell" ".dmg") (.deleteOnExit))
        sign (launcher/dispatch ["release" "sign"
                                 "--target" "macos"
                                 "--manifest-edn" manifest
                                 "--app-bundle" (.getPath app)
                                 "--dmg" (.getPath dmg)
                                 "--codesign-identity" "Developer ID Application: Demo"
                                 "--notary-profile" "kotoba-notary"
                                 "--updater-key" "updater-key"
                                 "--sign-command" "/bin/echo"
                                 "--sign-command-arg" "signed"
                                 "--execute"])
        submit (launcher/dispatch ["release" "submit"
                                   "--target" "macos"
                                   "--manifest-edn" manifest
                                   "--app-bundle" (.getPath app)
                                   "--dmg" (.getPath dmg)
                                   "--codesign-identity" "Developer ID Application: Demo"
                                   "--notary-profile" "kotoba-notary"
                                   "--updater-key" "updater-key"
                                   "--sign-command" "/bin/echo"
                                   "--sign-command-arg" "signed"
                                   "--submit-command" "/bin/echo"
                                   "--submit-command-arg" "submitted"
                                   "--execute"])
        farm-plan (launcher/dispatch ["device-farm" "check"
                                      "--target" "ios"
                                      "--device-farm-command" "/bin/echo"
                                      "--device-farm-command-arg" "farm-ok"])
        farm-execute (launcher/dispatch ["device-farm" "check"
                                         "--target" "ios"
                                         "--device-farm-command" "/bin/echo"
                                         "--device-farm-command-arg" "farm-ok"
                                         "--execute"])]
    (is (:kotoba.cli/ok? sign))
    (is (= :shell/release-signed (:kotoba.cli/code sign)))
    (is (= true (get-in sign [:kotoba.cli/data :kotoba.shell/release-rows 0 :signing-executed?])))
    (is (= "signed\n"
           (get-in sign [:kotoba.cli/data :kotoba.shell/release-rows 0 :sign-step :stdout])))
    (is (:kotoba.cli/ok? submit))
    (is (= :shell/release-submitted (:kotoba.cli/code submit)))
    (is (= true (get-in submit [:kotoba.cli/data :kotoba.shell/release-rows 0 :submitted?])))
    (is (= "submitted\n"
           (get-in submit [:kotoba.cli/data :kotoba.shell/release-rows 0 :submit-step :stdout])))
    (is (:kotoba.cli/ok? farm-plan))
    (is (= false
           (get-in farm-plan [:kotoba.cli/data :kotoba.shell/device-farm-rows 0 :device-farm-step :executed?])))
    (is (:kotoba.cli/ok? farm-execute))
    (is (= true
           (get-in farm-execute [:kotoba.cli/data :kotoba.shell/device-farm-rows 0 :device-farm-step :executed?])))
    (is (= "farm-ok\n"
           (get-in farm-execute [:kotoba.cli/data :kotoba.shell/device-farm-rows 0 :device-farm-step :stdout])))))

(deftest release-pipeline-generates-platform_default_plans
  (let [manifest "{:app/id \"kotoba.demo\" :app/name \"Kotoba Demo\" :app/version \"0.1.0\" :ios/bundle-id \"dev.kotoba.demo\" :android/application-id \"dev.kotoba.demo\"}"
        archive (doto (java.io.File/createTempFile "kotoba-shell" ".xcarchive") (.deleteOnExit))
        ipa (doto (java.io.File/createTempFile "kotoba-shell" ".ipa") (.deleteOnExit))
        apk (doto (java.io.File/createTempFile "kotoba-shell" ".apk") (.deleteOnExit))
        aab (doto (java.io.File/createTempFile "kotoba-shell" ".aab") (.deleteOnExit))
        ios-sign (launcher/dispatch ["release" "sign"
                                     "--target" "ios"
                                     "--manifest-edn" manifest
                                     "--xcode-archive" (.getPath archive)
                                     "--ipa" (.getPath ipa)
                                     "--apple-team-id" "TEAM123"
                                     "--app-store-key" "asc-key"
                                     "--provisioning-profile" "profile"])
        android-submit (launcher/dispatch ["release" "submit"
                                           "--target" "android"
                                           "--manifest-edn" manifest
                                           "--apk" (.getPath apk)
                                           "--aab" (.getPath aab)
                                           "--keystore" "keystore"
                                           "--keystore-alias" "release"
                                           "--play-service-account" "play-json"])
        updater (launcher/dispatch ["updater" "publish"
                                    "--target" "android"
                                    "--manifest-edn" manifest
                                    "--apk" (.getPath apk)
                                    "--aab" (.getPath aab)
                                    "--keystore" "keystore"
                                    "--keystore-alias" "release"
                                    "--play-service-account" "play-json"
                                    "--updater-command" "/bin/echo"
                                    "--updater-command-arg" "updater"
                                    "--execute"])]
    (is (:kotoba.cli/ok? ios-sign))
    (is (= :xcode-export-ipa
           (get-in ios-sign [:kotoba.cli/data :kotoba.shell/release-rows 0 :sign-step :platform-step])))
    (is (= true
           (get-in ios-sign [:kotoba.cli/data :kotoba.shell/release-rows 0 :sign-step :default?])))
    (is (= false
           (get-in ios-sign [:kotoba.cli/data :kotoba.shell/release-rows 0 :sign-step :executed?])))
    (is (:kotoba.cli/ok? android-submit))
    (is (= :google-play-publish
           (get-in android-submit [:kotoba.cli/data :kotoba.shell/release-rows 0 :submit-step :platform-step])))
    (is (:kotoba.cli/ok? updater))
    (is (= :shell/updater-published (:kotoba.cli/code updater)))
    (is (= true
           (get-in updater [:kotoba.cli/data :kotoba.shell/release-rows 0 :updater-published?])))
    (is (= "updater\n"
           (get-in updater [:kotoba.cli/data :kotoba.shell/release-rows 0 :updater-step :stdout])))))

(deftest doctor-check-reports-toolchain-readiness
  (let [macos (launcher/dispatch ["doctor" "check" "--target" "macos"])
        mobile (launcher/dispatch ["doctor" "check"
                                   "--target" "ios"
                                   "--target" "android"])
        strict-mobile (launcher/dispatch ["doctor" "check"
                                          "--target" "ios"
                                          "--target" "android"
                                          "--strict"])]
    (is (:kotoba.cli/ok? macos))
    (is (= "kotoba.shell.doctor.v0"
           (get-in macos [:kotoba.cli/data :kotoba.shell/doctor-schema])))
    (is (= 1 (get-in macos [:kotoba.cli/data :kotoba.shell/target-count])))
    (is (= :macos (get-in macos [:kotoba.cli/data :kotoba.shell/doctor-rows 0 :target])))
    (is (true? (get-in macos [:kotoba.cli/data :kotoba.shell/doctor-rows 0 :host-runner-ready?])))
    (is (seq (get-in macos [:kotoba.cli/data :kotoba.shell/doctor-rows 0 :tools])))
    (is (:kotoba.cli/ok? mobile))
    (is (= 2 (get-in mobile [:kotoba.cli/data :kotoba.shell/target-count])))
    (is (#{:shell/doctor-ready :shell/doctor-warnings}
         (:kotoba.cli/code mobile)))
    (is (= true (get-in strict-mobile [:kotoba.cli/data :kotoba.shell/strict?])))
    (is (#{:shell/doctor-ready :shell/doctor-blocked}
         (:kotoba.cli/code strict-mobile)))))

(deftest e2e-check-reports-target-readiness-and-smoke
  (let [macos (launcher/dispatch ["e2e" "check" "--target" "macos"])
        mobile (launcher/dispatch ["e2e" "check"
                                   "--target" "ios"
                                   "--target" "android"])
        strict-mobile (launcher/dispatch ["e2e" "check"
                                          "--target" "ios"
                                          "--target" "android"
                                          "--strict"])]
    (is (:kotoba.cli/ok? macos))
    (is (= "kotoba.shell.e2e.v0"
           (get-in macos [:kotoba.cli/data :kotoba.shell/e2e-schema])))
    (is (= 1 (get-in macos [:kotoba.cli/data :kotoba.shell/target-count])))
    (is (= :macos (get-in macos [:kotoba.cli/data :kotoba.shell/e2e-rows 0 :target])))
    (is (true? (get-in macos [:kotoba.cli/data :kotoba.shell/e2e-rows 0 :surface-ready?])))
    (is (true? (get-in macos [:kotoba.cli/data :kotoba.shell/e2e-rows 0 :provider-bridge-ready?])))
    (is (true? (get-in macos [:kotoba.cli/data :kotoba.shell/e2e-rows 0 :release-metadata-ready?])))
    (is (= true (get-in macos [:kotoba.cli/data :kotoba.shell/e2e-rows 0 :host-smoke :ran?])))
    (is (:kotoba.cli/ok? mobile))
    (is (= 2 (get-in mobile [:kotoba.cli/data :kotoba.shell/target-count])))
    (is (#{:shell/e2e-ready :shell/e2e-warnings}
         (:kotoba.cli/code mobile)))
    (is (= true (get-in strict-mobile [:kotoba.cli/data :kotoba.shell/strict?])))
    (is (#{:shell/e2e-ready :shell/e2e-blocked}
         (:kotoba.cli/code strict-mobile)))))

(deftest mobile-host-smoke-parses-real-device-output
  (is (launcher/ios-booted-device? "iPhone 16 Pro (0000-1111) (Booted)\n"))
  (is (false? (launcher/ios-booted-device? "iPhone 16 Pro (0000-1111) (Shutdown)\n")))
  (is (launcher/android-connected-device? "List of devices attached\nemulator-5554\tdevice\n"))
  (is (false? (launcher/android-connected-device? "List of devices attached\nemulator-5554\toffline\n")))
  (is (launcher/host-smoke-ok? :ios "iPhone 16 Pro (Booted)\n" 0 false))
  (is (false? (launcher/host-smoke-ok? :ios "iPhone 16 Pro (Shutdown)\n" 0 false)))
  (is (launcher/host-smoke-ok? :android "List of devices attached\nserial1 device\n" 0 false))
  (is (false? (launcher/host-smoke-ok? :android "List of devices attached\n" 0 false))))

(deftest ui-check-connects-browser-and-wasm-ui-substrates
  (let [result (launcher/dispatch ["ui" "check"])
        strict-result (launcher/dispatch ["ui" "check" "--strict"])]
    (is (:kotoba.cli/ok? result))
    (is (= :shell/ui-ready (:kotoba.cli/code result)))
    (is (= "kotoba.shell.ui.v0"
           (get-in result [:kotoba.cli/data :kotoba.shell/ui-schema])))
    (is (= false (get-in result [:kotoba.cli/data :kotoba.shell/webview-required?])))
    (is (= 2 (get-in result [:kotoba.cli/data :kotoba.shell/substrate-count])))
    (is (= #{:wasm-ui :browser}
           (set (map :id (get-in result [:kotoba.cli/data :kotoba.shell/ui-rows])))))
    (is (every? :ready? (get-in result [:kotoba.cli/data :kotoba.shell/ui-rows])))
    (is (= :ui/ready
           (get-in result [:kotoba.cli/data :kotoba.shell/audit :audit/event])))
    (is (:kotoba.cli/ok? strict-result))
    (is (= true (get-in strict-result [:kotoba.cli/data :kotoba.shell/strict?])))))

(deftest ui-smoke-plans-browser-and-wasm-ui-gates
  (let [result (launcher/dispatch ["ui" "smoke"])
        browser-only (launcher/dispatch ["ui" "smoke"
                                         "--substrate" "browser"
                                         "--script" "smoke:visual"
                                         "--strict"])]
    (is (:kotoba.cli/ok? result))
    (is (= :shell/ui-smoke-ready (:kotoba.cli/code result)))
    (is (= "kotoba.shell.ui-smoke.v0"
           (get-in result [:kotoba.cli/data :kotoba.shell/ui-smoke-schema])))
    (is (= false (get-in result [:kotoba.cli/data :kotoba.shell/execute?])))
    (is (= 5 (get-in result [:kotoba.cli/data :kotoba.shell/smoke-count])))
    (is (every? :present? (get-in result [:kotoba.cli/data :kotoba.shell/smoke-rows])))
    (is (every? #(= false (:executed? %))
                (get-in result [:kotoba.cli/data :kotoba.shell/smoke-rows])))
    (is (= :ui-smoke/ready
           (get-in result [:kotoba.cli/data :kotoba.shell/audit :audit/event])))
    (is (:kotoba.cli/ok? browser-only))
    (is (= 1 (get-in browser-only [:kotoba.cli/data :kotoba.shell/smoke-count])))
    (is (= :browser
           (get-in browser-only [:kotoba.cli/data :kotoba.shell/smoke-rows 0 :substrate])))
    (is (= "smoke:visual"
           (get-in browser-only [:kotoba.cli/data :kotoba.shell/smoke-rows 0 :script])))
    (is (= false
           (get-in browser-only [:kotoba.cli/data :kotoba.shell/smoke-rows 0 :served?])))))

(deftest browser-smoke-command-waits-for-static-server
  (let [command (launcher/browser-smoke-shell-command "smoke:webgpu")]
    (is (re-find #"http://127\.0\.0\.1:8702/" command))
    (is (re-find #"urllib\.request\.urlopen" command))
    (is (re-find #"npm run smoke:webgpu" command))))

(defn -main
  [& _]
  (let [{:keys [fail error]} (clojure.test/run-tests 'kotoba.shell.launcher-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
