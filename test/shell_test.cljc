(ns shell-test
  (:require [clojure.test :refer [deftest is testing]]
            shell))

(deftest namespace-loads
  (testing "the restored CLJC namespace loads"
    (is (some? (find-ns 'shell)))))
