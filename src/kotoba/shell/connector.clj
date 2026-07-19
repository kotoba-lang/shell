(ns kotoba.shell.connector
  "Shell-free subprocess boundary shared by native Kotoba applications.

  Callers own the domain payload and codec. This namespace only guarantees an
  argv-only process, stdin/stdout framing, bounded result shape, and redacted
  failure data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn argv
  "Decode and validate an argv value from an environment string."
  [env-name raw decode]
  (when-not (str/blank? (str raw))
    (let [value (decode raw)]
      (when-not (and (vector? value) (seq value) (every? string? value))
        (throw (ex-info "connector argv must be a non-empty vector of strings"
                        {:env env-name})))
      value)))

(defn invoke!
  "Invoke argv without a command shell. `encode` and `decode` frame one value.
  Success is decided by `success?`; stdout/stderr are never included together
  with credentials or the input payload."
  [{:keys [argv input encode decode success?]}]
  (let [proc (.start (ProcessBuilder. ^java.util.List argv))]
    (with-open [w (io/writer (.getOutputStream proc))]
      (.write w (encode input))
      (.write w "\n"))
    (let [stdout (slurp (.getInputStream proc))
          stderr (slurp (.getErrorStream proc))
          exit (.waitFor proc)
          result (when-not (str/blank? stdout)
                   (try (decode stdout) (catch Exception _ nil)))]
      (if (and (zero? exit) result (success? result))
        result
        {:ok? false :error :connector-failed :exit exit
         :message (if (str/blank? stderr)
                    "connector returned no valid success value"
                    (subs stderr 0 (min 4096 (count stderr))))}))))
