(ns kotoba.shell.connector-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.shell.connector :as connector]))

(deftest connector-timeout-is-bounded-and-redacted
  (let [started (System/nanoTime)
        result (connector/invoke! {:argv ["/bin/sleep" "5"]
                                   :input {:secret "must-not-leak"}
                                   :encode pr-str
                                   :decode read-string
                                   :success? :ok?
                                   :timeout-ms 50})
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
    (is (= :connector-timeout (:error result)))
    (is (= 124 (:exit result)))
    (is (< elapsed-ms 2000))
    (is (not (re-find #"must-not-leak" (pr-str result))))))
