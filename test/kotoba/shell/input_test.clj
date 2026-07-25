(ns kotoba.shell.input-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shell.input :as input]))

(deftest typed-input-action-contract
  (is (= {:id "message" :placeholder "Reply"}
         (input/field "message" {:placeholder "Reply"})))
  (is (= {:data-action "chat/send:r1" :data-input-id "message"}
         (input/submit "chat/send:r1" "message")))
  (testing "blank identifiers fail before reaching a native renderer"
    (is (thrown? clojure.lang.ExceptionInfo (input/field "")))
    (is (thrown? clojure.lang.ExceptionInfo (input/submit "chat/send" "")))))
