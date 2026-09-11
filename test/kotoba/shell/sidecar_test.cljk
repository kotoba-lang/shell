(ns kotoba.shell.sidecar-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shell.sidecar :as sidecar]))

(def ^:private studio
  "The manifest that replaces murakumo-studio's Tauri crate."
  {:sidecar/command ["clojure" "-M:engine"]
   :sidecar/find-root-marker "deps.edn"
   :sidecar/env-overrides {"MURAKUMO_STUDIO_REPO" :sidecar/dir
                           "MURAKUMO_STUDIO_ENGINE_ALIAS" :sidecar/alias}})

(defn- host [start ancestors marker-dirs]
  {:start start :ancestors ancestors
   :contains-marker? (set marker-dirs)
   :env {}})

(deftest a-manifest-without-a-sidecar-is-fine
  (testing "most apps are just a window"
    (is (not (sidecar/declared? {:app/id "demo"})))
    (is (sidecar/declared? studio))))

(deftest every-problem-is-reported-at-once
  (testing "a hand-edited manifest should learn its whole shape in one pass"
    (let [issues (sidecar/problems {})]
      (is (= 2 (count issues)))
      (is (some #(re-find #":sidecar/command" %) issues))
      (is (some #(re-find #"somewhere to run" %) issues)))))

(deftest a-command-must-be-a-real-command
  (doseq [bad [nil [] "clojure -M:engine" ["clojure" ""] ["clojure" nil]]]
    (is (seq (sidecar/problems (assoc studio :sidecar/command bad)))
        (str (pr-str bad) " must be refused"))))

(deftest an-explicit-directory-wins
  (is (= {:dir "/srv/app"}
         (sidecar/resolve-dir (host "/anywhere" ["/anywhere"] #{})
                              (assoc studio :sidecar/dir "/srv/app")))))

(deftest the-marker-search-walks-outward
  (testing "the nearest ancestor holding the marker is the answer"
    (is (= {:dir "/repo"}
           (sidecar/resolve-dir
            (host "/repo/tauri/src-tauri"
                  ["/repo/tauri/src-tauri" "/repo/tauri" "/repo" "/"]
                  #{"/repo"})
            studio)))))

(deftest a-failed-search-is-an-error-not-a-guess
  ;; The Rust fell back to $HOME/murakumo-studio, which starts the engine in
  ;; the wrong place and reports success. An error is the only honest answer.
  (let [{:keys [dir error]}
        (sidecar/resolve-dir (host "/tmp/x" ["/tmp/x" "/tmp" "/"] #{}) studio)]
    (is (nil? dir))
    (is (re-find #"no ancestor of" error))
    (is (re-find #"deps.edn" error))))

(deftest env-may-override-only-what-the-manifest-declares
  (testing "a declared override applies"
    (is (= "/elsewhere"
           (:sidecar/dir (sidecar/apply-env-overrides
                          studio {"MURAKUMO_STUDIO_REPO" "/elsewhere"})))))
  (testing "an undeclared variable changes nothing"
    (is (= studio (sidecar/apply-env-overrides studio {"PATH" "/usr/bin"}))))
  (testing "a blank value is not an override"
    (is (= studio (sidecar/apply-env-overrides studio {"MURAKUMO_STUDIO_REPO" "  "})))))

(deftest spec-decides-everything-in-one-call
  (testing "the studio manifest, resolved"
    (is (= {:command ["clojure" "-M:engine"] :dir "/repo"}
           (sidecar/spec (host "/repo/tauri" ["/repo/tauri" "/repo" "/"] #{"/repo"})
                         studio))))
  (testing "env override reaches the answer"
    (is (= {:command ["clojure" "-M:engine"] :dir "/elsewhere"}
           (sidecar/spec (assoc (host "/repo/tauri" ["/repo/tauri" "/repo"] #{"/repo"})
                                :env {"MURAKUMO_STUDIO_REPO" "/elsewhere"})
                         studio))))
  (testing "an unresolvable manifest yields an error, never a partial spec"
    (let [r (sidecar/spec (host "/tmp" ["/tmp" "/"] #{}) studio)]
      (is (:error r))
      (is (nil? (:command r))))))
