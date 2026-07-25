(ns kotoba.shell.tamaki-observer
  "Read-only native projection of Tamaki's durable event stream."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [kotoba.tamaki.loop :as agent-loop]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store]))

(defn state-dir []
  (or (System/getenv "TAMAKI_STATE_DIR") "../../../tamaki/.tamaki"))

(defn- newest-loop-log []
  (->> (or (.listFiles (io/file (state-dir))) (make-array java.io.File 0))
       (filter #(re-matches #"loop-.*\.log" (.getName %)))
       (sort-by #(.lastModified %) >)
       first))

(defn- tail-text [file max-bytes]
  (if-not file
    ""
    (with-open [raf (java.io.RandomAccessFile. file "r")]
      (let [length (.length raf)
            start (max 0 (- length max-bytes))
            bytes (byte-array (- length start))]
        (.seek raf start)
        (.readFully raf bytes)
        (String. bytes java.nio.charset.StandardCharsets/UTF_8)))))

(defn activity-lines []
  (->> (str/split-lines (tail-text (newest-loop-log) 65536))
       (filter #(or (str/starts-with? % "[tool:")
                    (str/starts-with? % "-- ")
                    (str/starts-with? % "{:patch/id")
                    (str/starts-with? % "{:loop/id")
                    (str/includes? % "cycle failed")))
       (map #(str/replace % #"\s+" " "))
       (take-last 12)
       vec))

(defn snapshot
  ([] (assoc (snapshot (store/read-local-events (state-dir)))
             :activity (activity-lines)
             :observed-at (System/currentTimeMillis)))
  ([events]
   (let [campaigns (agent-loop/campaigns events)
         runs (model/fold-events events)
         kinds (frequencies (map :tamaki.event/kind events))]
     {:events (count events)
      :campaigns (->> (vals campaigns)
                      (sort-by :tamaki.loop/updated-at >) vec)
      :runs (->> (vals runs)
                 (filter :agent.run/id)
                 (remove #(str/starts-with? (:agent.run/id %) "loop-"))
                 (sort-by :agent.run/updated-at >) vec)
      :patches (get kinds :patch/created 0)
      :integrations (get kinds :patch/integrated 0)
      :failures (+ (get kinds :run/failed 0)
                   (get kinds :loop/cycle-failed 0))
      :latest-at (reduce max 0 (keep :tamaki.event/at events))})))

(defn- truncate [value n]
  (let [s (str value)]
    (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s)))

(defn- ops-builder []
  (let [next-id (atom 0) ops (atom [])]
    {:element
     (fn [tag attrs children]
       (let [id (swap! next-id inc)]
         (swap! ops conj [:dom/create-element id tag])
         (doseq [[k v] attrs] (swap! ops conj [:dom/set-attr id k v]))
         (doseq [child children] (swap! ops conj [:dom/append-child id child]))
         id))
     :text
     (fn [value]
       (let [id (swap! next-id inc)]
         (swap! ops conj [:dom/create-text id (str value)])
         id))
     :finish (fn [root] (conj @ops [:dom/set-root root]))}))

(defn surface-ops [state]
  (let [{:keys [element text finish]} (ops-builder)
        label (fn [tag value] (element tag {} [(text value)]))
        metric (fn [name value]
                 (element :article {"class" "liquid-glass__panel"}
                          [(label :h3 name) (label :h1 value)]))
        campaign-card
        (fn [campaign]
          (element :section {"class" "liquid-glass__panel"}
                   [(label :h2 (str "環 " (:tamaki.loop/id campaign)))
                    (label :p (truncate (:tamaki.loop/objective campaign) 120))
                    (label :p
                           (str "status " (name (:tamaki.loop/status campaign))
                                "  ·  cycles " (:tamaki.loop/cycles campaign)
                                "/" (:tamaki.loop/max-cycles campaign)
                                "  ·  failures " (:tamaki.loop/failures campaign)
                                "/" (:tamaki.loop/max-failures campaign)))
                    (label :p (str "last " (name (or (:tamaki.loop/last-result campaign)
                                                     :waiting))))]))
        run-row
        (fn [run]
          (element :article {}
                   [(label :h3 (str (name (:agent.run/status run)) "  "
                                    (:agent.run/id run)))
                    (label :p (truncate (:agent.run/goal run) 150))]))
        header (element :header {}
                        [(element :section {}
                                  [(label :h1 "Tamaki Observatory")
                                   (label :p "Durable recursive improvement · Radicle canonical")])
                         (label :h3 (if (pos? (:latest-at state))
                                      "● event stream online"
                                      "○ waiting for events"))])
        metrics (element :summary {}
                         [(metric "Events" (:events state))
                          (metric "Patches" (:patches state))
                          (metric "Integrated" (:integrations state))
                          (metric "Failures" (:failures state))])
        campaign-section
        (element :section {}
                 (into [(label :h2 "Growth campaigns")]
                       (if (seq (:campaigns state))
                         (mapv campaign-card (:campaigns state))
                         [(label :p "No durable campaign has been observed.")])))
        run-section
        (element :section {}
                 (into [(label :h2 "Recent AgentRuns")]
                       (if (seq (:runs state))
                         (mapv run-row (take 8 (:runs state)))
                         [(label :p "No AgentRun receipts yet.")])))
        activity-section
        (element :section {"class" "liquid-glass__panel"}
                 (into [(label :h2 "Live activity")
                        (label :p (str "updated "
                                       (java.time.Instant/ofEpochMilli
                                        (or (:observed-at state) 0))))]
                       (if (seq (:activity state))
                         (mapv #(label :p (truncate % 180)) (:activity state))
                         [(label :p "Waiting for agent tool activity…")])))
        root (element :main {}
                      [header metrics activity-section campaign-section run-section])]
    (finish root)))

(defn start []
  {:kotoba.app/surface-ops (surface-ops (snapshot))})
