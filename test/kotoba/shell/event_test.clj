(ns kotoba.shell.event-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.shell.event :as event]
            [kotoba.shell.input :as input]))

(deftest decodes-the-events-the-appkit-host-actually-prints
  ;; These maps are the JSON the host emits, parsed. Keeping them literal is
  ;; the point: if the host's wire format drifts, this test should be what
  ;; notices, not a running app.
  (testing "a plain action"
    (is (= {:event/kind :action :event/action "chat/send"}
           (event/decode {"event" "input/action" "action" "chat/send"}))))

  (testing "an action carrying the bound control's text"
    (is (= {:event/kind :action :event/action "chat/send" :event/value "hello"}
           (event/decode {"event" "input/action" "action" "chat/send"
                          "value" "hello"}))))

  (testing "empty input is distinguishable from no input at all"
    (is (= "" (:event/value (event/decode {"event" "input/action"
                                           "action" "chat/send"
                                           "value" ""}))))
    (is (not (contains? (event/decode {"event" "input/action" "action" "chat/send"})
                        :event/value))))

  (testing "host payload the decoder does not name survives in :event/data"
    (is (= {:event/kind :action
            :event/action "ingest/file-selected"
            :event/data {"path" "/tmp/x.png"}}
           (event/decode {"event" "input/action"
                          "action" "ingest/file-selected"
                          "path" "/tmp/x.png"}))))

  (testing "cancellation is its own kind, not a submit"
    (let [cancelled (event/decode {"event" "input/action-cancelled"
                                   "action" "ingest/pick-file"})]
      (is (= :action-cancelled (:event/kind cancelled)))
      (is (false? (event/action? cancelled)))))

  (testing "resize and lifecycle"
    (is (= {:event/kind :resize :event/width 1180 :event/height 820}
           (event/decode {"event" "input/resize" "width" 1180 "height" 820})))
    (is (= {:event/kind :terminate}
           (event/decode {"event" "lifecycle/terminate"})))
    (is (= {:event/kind :launch :event/data {"surface" "kotoba:dom" "ops" 3}}
           (event/decode {"event" "lifecycle/launch"
                          "surface" "kotoba:dom" "ops" 3})))))

(deftest rejects-what-an-app-must-not-dispatch-on
  (testing "an unknown event is refused rather than passed through"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown host event"
                          (event/decode {"event" "input/telepathy"}))))
  (testing "a harness screenshot event is not an app event"
    ;; The host really does print this one, so silently ignoring it would let a
    ;; test artifact reach application state.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"harness event"
                          (event/decode {"event" "visual/captured"
                                         "ok" true "path" "/tmp/a.png"}))))
  (testing "an action without an action name is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank action"
                          (event/decode {"event" "input/action" "action" "  "})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (event/decode {"event" "input/action"}))))
  (is (thrown? clojure.lang.ExceptionInfo (event/decode "input/action"))))

(deftest action-constructor-round-trips-with-the-input-contract
  ;; kotoba.shell.input names the action on the way out; event/action is the
  ;; same name coming back. If these two ever disagree, a submit button and its
  ;; handler stop matching.
  (let [attrs (input/submit "chat/send:r1" "message")
        incoming (event/action (:data-action attrs) "hello")]
    (is (= "chat/send:r1" (:event/action incoming)))
    (is (= "hello" (:event/value incoming)))
    (is (true? (event/action? incoming)))
    (is (= incoming (event/decode {"event" "input/action"
                                   "action" "chat/send:r1"
                                   "value" "hello"}))))
  (is (thrown? clojure.lang.ExceptionInfo (event/action ""))))

(deftest step-result-fixes-the-shape-an-app-returns
  (let [ops [[:dom/create-element 1 :main] [:dom/set-root 1]]]
    (testing "state, ops and effects come back normalized"
      (is (= {:app/state {:count 1}
              :kotoba.app/surface-ops ops
              :app/effects [[:http/post {:url "http://127.0.0.1:8088"}]]}
             (event/step-result
              {:app/state {:count 1}
               :kotoba.app/surface-ops ops
               :app/effects [[:http/post {:url "http://127.0.0.1:8088"}]]}))))

    (testing "effects default to none rather than nil"
      (is (= [] (:app/effects (event/step-result {:kotoba.app/surface-ops ops})))))

    (testing "an app that returns no surface cannot silently blank the window"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                            (event/step-result {:kotoba.app/surface-ops []})))
      (is (thrown? clojure.lang.ExceptionInfo (event/step-result {}))))

    (testing "effects are data, never thunks -- a callable is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyed by a keyword"
                            (event/step-result {:kotoba.app/surface-ops ops
                                                :app/effects [(fn [] :boom)]}))))))

(deftest contract-file-and-implementation-agree
  ;; The EDN is the published contract; drift between it and the namespace that
  ;; implements it is exactly the failure this catches.
  (let [contract (edn/read-string
                  (slurp (io/resource "kotoba/shell/selfhost/app_event_contract.edn")))]
    (is (= event/contract (:schema contract)))
    (is (= "kotoba.shell.event" (:implementation contract)))
    (is (= (set (map :kind (vals (:host-events contract))))
           (set event/kinds))
        "every kind the contract publishes must be a kind decode can produce")
    (doseq [[wire spec] (:host-events contract)]
      (is (= (:kind spec)
             (:event/kind (event/decode (cond-> {"event" wire}
                                          (get-in spec [:fields :action]) (assoc "action" "a")
                                          (get-in spec [:fields :width]) (assoc "width" 1 "height" 2)))))
          (str "contract kind for " wire " must match decode")))
    (doseq [wire (:harness-events contract)]
      (is (thrown? clojure.lang.ExceptionInfo (event/decode {"event" wire}))
          (str wire " is published as a harness event and must not decode")))))
