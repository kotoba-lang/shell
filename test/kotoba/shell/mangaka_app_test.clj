(ns kotoba.shell.mangaka-app-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shell.apps.mangaka :as mangaka]))

(deftest mangaka-local-app-exposes-generation-and-preview
  (let [ops (:kotoba.app/surface-ops (mangaka/start))
        attrs (filter #(= :dom/set-attr (first %)) ops)]
    (testing "the surface is a non-empty Kotoba DOM program"
      (is (seq ops))
      (is (= [:dom/set-root 1] (last ops))))
    (testing "generation stays on the loopback Mangaka API"
      (is (= #{"mangaka/generate-page" "mangaka/review-page"
               "mangaka/export-genko" "mangaka/generate-panel"}
             (set (keep #(when (= :data-action (nth % 2 nil)) (nth % 3 nil)) attrs))))
      (is (some #(and (= :data-endpoint (nth % 2 nil))
                      (= "http://127.0.0.1:8088/runs" (nth % 3 nil))) attrs)))
    (testing "the latest composed page is rendered as a native image"
      (is (some #(and (= :dom/create-element (first %))
                      (= :img (nth % 2 nil))) ops)))))
